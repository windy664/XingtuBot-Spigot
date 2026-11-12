package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.handler.PermissionChecker;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.runtime.BotRuntimeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * AI 群聊处理器（平台中立）——编排层。
 * 触发护栏 / 插话节奏 / 提示词构造分别在
 * {@link AiGuards}、{@link ChimeInController}、{@link AiPromptBuilder}。
 *
 * <h3>核心设计</h3>
 * <ol>
 *   <li><b>旁听</b>：所有群消息记录到 {@link GroupContextMemory}，AI 知道群里在聊什么。</li>
 *   <li><b>超管立场</b>：长期记忆超管的观点（{@link AdminStanceMemory}），有人跟超管观点冲突时主动反驳。</li>
 *   <li><b>服务器知识库</b>：xt-group 的自定义问答经服务总线（JDK Supplier 软依赖）取来，
 *       词汇级粗检索挑 top-K 注入系统词，玩家换着问法问服务器问题时也能按配置口径回答。</li>
 *   <li><b>@触发</b>：@机器人 时直接回复（跳过已注册命令和配置的关键词黑名单）。</li>
 *   <li><b>自主参与</b>：三层漏斗——规则打分器粗筛 → 便宜模型判定 → 回复概率，
 *       再用群聊节奏限制插话频率。跳过命令消息。</li>
 *   <li><b>群友画像</b>：记住群里的人（{@link UserProfile}），能自然提起前情。</li>
 * </ol>
 *
 * <h3>线程模型（重要）</h3>
 * <pre>
 * QQ WS 线程 → matches()   ← 纯计算 + 零 IO 的旁听，绝不发网络请求
 *          → handle()      ← 只做一次限频 + 入队，立刻返回
 *          → AiExecutor    ← judge / 生成 / 发送都在这里
 * </pre>
 * 旧版把 judge（一次 LLM 调用）放在 {@code matches()} 里，而 {@code matches()} 跑在
 * QQ 网关的 WebSocket 线程上——一次卡顿（读超时最长 25 秒）会把整条入站消息链堵住，
 * 其他模块的 @ 指令也一起停摆。现在 LLM 调用全部移到 {@link AiExecutor}。
 *
 * <h3>防刷机制（API 调用前拦截，不是调了再截断）</h3>
 * <ul>
 *   <li>命令关键词黑名单 → matches() 就返回 false，API 根本不会调（{@link AiGuards}）</li>
 *   <li>规则打分器低分 → 不插话，<b>连 judge 都不调</b>（{@link ReplyNecessityScorer}）</li>
 *   <li>全局每小时调用上限 → 超限后所有触发静默跳过（judge 与生成都计入）</li>
 *   <li>单用户频率限制 → 同一用户1分钟内最多N次</li>
 *   <li>输入截断 → 超长消息截到300字</li>
 * </ul>
 */
public final class AiChatHandler implements BotMessageHandler {

    private static final int MAX_INPUT_LENGTH = 300;
    /** 复读检测：拿最近这么多条机器人回复做相似度比对。 */
    private static final int RECENT_REPLY_KEEP = 10;

    private final AiService aiService;
    private final BotConfig config;
    private final BotLogger logger;
    private final BiPredicate<String, BotMessageContext> commandMatcher;
    private final PermissionChecker permission;
    private final AiChatMemory replyMemory = new AiChatMemory();
    private final GroupContextMemory groupContext = new GroupContextMemory();
    private final UserProfile userProfile = new UserProfile();
    private final AdminStanceMemory adminStance;
    private final AiGuards guards;
    private final ChimeInController chimeIn;
    // 服务器知识库来源（服务总线懒查询的 Supplier<List<String>>，xt-group 未装时取到 null）
    private final Supplier<List<String>> knowledgeSource;
    // AI 专属线程池：LLM 调用绝不在网关线程上发生
    private final AiExecutor executor;
    // 人格卡（身份 / 行为 / 表达 / 忌讳 / few-shot）
    private final AiPersona persona;
    // 输出后处理管线：所有回复的唯一出口
    private final AiPostProcessor postProcessorChimeIn;
    private final AiPostProcessor postProcessorDirectAt;
    // 每群最近几条机器人回复（复读检测用）
    private final ConcurrentHashMap<String, List<String>> recentBotReplies = new ConcurrentHashMap<>();

    public AiChatHandler(AiService aiService, BotConfig config, BotLogger logger,
                         BiPredicate<String, BotMessageContext> commandMatcher, PermissionChecker permission,
                         java.io.File dataDir) {
        this(aiService, null, config, logger, commandMatcher, permission, dataDir, null, null);
    }

    public AiChatHandler(AiService aiService, AiService chimeInJudgeService, BotConfig config, BotLogger logger,
                         BiPredicate<String, BotMessageContext> commandMatcher, PermissionChecker permission,
                         java.io.File dataDir, Supplier<List<String>> knowledgeSource) {
        this(aiService, chimeInJudgeService, config, logger, commandMatcher, permission,
                dataDir, knowledgeSource, null);
    }

    public AiChatHandler(AiService aiService, AiService chimeInJudgeService, BotConfig config, BotLogger logger,
                         BiPredicate<String, BotMessageContext> commandMatcher, PermissionChecker permission,
                         java.io.File dataDir, Supplier<List<String>> knowledgeSource,
                         AiExecutor executor) {
        this.aiService = aiService;
        this.config = config;
        this.logger = logger;
        this.commandMatcher = commandMatcher;
        this.permission = permission;
        this.adminStance = new AdminStanceMemory(dataDir);
        this.adminStance.setAiService(aiService, logger);
        this.adminStance.configure(
                config.getBoolean("ai-admin-stance-enable", true),
                config.getBoolean("ai-admin-stance-inject-pending", false),
                config.getInt("ai-admin-stance-max-pending-inject", 3),
                config.getStringList("ai-admin-stance-privacy-filter"));
        this.guards = new AiGuards(config);
        this.chimeIn = new ChimeInController(chimeInJudgeService, config, logger, groupContext);
        this.knowledgeSource = knowledgeSource;
        this.executor = executor != null ? executor : new AiExecutor(logger);
        this.persona = AiPersona.fromConfig(config);

        Map<String, String> pp = config.getStringMap("post-process");
        double splitProb = parseDouble(pp.get("split-probability"), 0.5);
        int maxParts = parseInt(pp.get("split-max-parts"), 3);
        this.postProcessorChimeIn = new AiPostProcessor(
                parseInt(pp.get("max-length-chime-in"), 120), splitProb, maxParts);
        this.postProcessorDirectAt = new AiPostProcessor(
                parseInt(pp.get("max-length-direct-at"), 200), splitProb, maxParts);
    }

    private static int parseInt(String raw, int def) {
        if (raw == null || raw.trim().isEmpty()) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String raw, double def) {
        if (raw == null || raw.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public void init(HandlerContext ctx) {}

    @Override
    public boolean matches(String message, BotMessageContext event) {
        if (message == null) return false;
        String et = event.getEventType();
        if (et != null && et.contains("MEMBER")) return false;

        // 群聊白名单：不在白名单里的群，旁听和回复都跳过
        if (event.isGroupMessage() && !guards.isGroupAllowed(event.getConversationId())) {
            return false;
        }

        String msg = message.trim();
        if (msg.isEmpty()) return false;

        if (isRegisteredCommand(msg, event)) return false;

        String senderId = event.getSenderId();
        boolean atBot = event.isGroupAtMessage();

        // 旁听 + 群友画像 + 超管立场记录。
        // ⚠️ 这里必须留在 matches()：AiChatHandler.matches 判定管理员不参与自主插话，
        //    管理员的消息根本不会走到 handle()，一旦把记录挪进 handle()，
        //    超管立场会静默失效（无任何报错）。
        //    磁盘写入由 AdminStanceMemory 的后台落盘器异步完成，这里不做 IO。
        if (event.isGroupMessage()) {
            String username = event.getUsername() != null ? event.getUsername() : "群友";
            groupContext.record(event.getConversationId(), username, msg, atBot);
            if (config.getBoolean("ai-user-profile-enable", true)) {
                userProfile.record(event.getConversationId(), senderId, username, msg, false);
            }

            if (permission != null && permission.isAdmin(senderId)) {
                adminStance.record(event.getConversationId(), msg);
            }
        }

        // --- 用户黑名单：永远不触发AI回复（旁听已记录，直接跳过）---
        if (guards.isBlacklisted(senderId)) return false;

        // --- 命令过滤：命中黑名单 → 不触发AI，API不会调 ---
        if (guards.isBlockedCommand(msg)) return false;

        // 触发条件1：@机器人 → 直答通道（跳过 judge 与打分器）
        if (atBot) {
            // 额度闸门：超了直接不处理，连线程池槽位都不占（旧版行为：静默跳过）
            if (!guards.budgetLeft()) return false;
            // 注意力：被 @ 说明现在话题有戏
            chimeIn.bumpAttention(event.getConversationId(), 20);
            return true;
        }

        // 触发条件2：攻击名单用户 → 直接进入攻击模式，跳过 judge 与冷却
        if (event.isGroupMessage() && guards.isAttackTarget(senderId)) {
            return guards.budgetLeft();
        }

        // 触发条件3：自主参与（普通用户，非攻击名单）
        if (event.isGroupMessage()) {
            // 管理员跳过自主参与（但攻击名单的管理员已在上面处理）
            if (permission != null && permission.isAdmin(senderId)) return false;
            if (!guards.budgetLeft()) return false;

            // ── 第一层漏斗：规则打分器 ──
            // 分太低直接不插话，连 judge 都不调 —— 这是省 token 的关键。
            int threshold = config.getInt("reply-necessity-threshold", 0);
            int score = chimeIn.scoreOf(msg, event.getConversationId());
            if (score < threshold) return false;

            // 提到机器人名字 → 注意力涨
            String botName = BotRuntimeState.getBotName();
            if (botName != null && !botName.isEmpty() && msg.contains(botName)) {
                chimeIn.bumpAttention(event.getConversationId(), 10);
            }

            chimeIn.recordCandidate(event.getConversationId());

            // 节奏门槛是纯计算，可以留在网关线程；judge 挪到 AI 线程池
            if (!chimeIn.canReply(event.getConversationId())) {
                return false;
            }
            return true;
        }

        return false;
    }

    private boolean isRegisteredCommand(String msg, BotMessageContext event) {
        if (commandMatcher == null) return false;
        try {
            return commandMatcher.test(msg, event);
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] command filter failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        final String rawMsg = message.trim();
        final String senderId = event.getSenderId();
        final boolean senderIsAdmin = permission != null && permission.isAdmin(senderId);
        final boolean isDirectAt = event.isGroupAtMessage();

        // 单用户频率限制（@触发也受限，但阈值更高）。
        // 放在这里而不是 AI 线程池里：限频没过就别占线程池槽位。
        if (!senderIsAdmin && !guards.checkRateLimit(event.getConversationId() + "#" + senderId, isDirectAt)) {
            return;
        }

        // 输入截断
        final String msg = rawMsg.length() > MAX_INPUT_LENGTH
                ? rawMsg.substring(0, MAX_INPUT_LENGTH)
                : rawMsg;

        // 真正的活儿交给 AI 专属线程池：judge / 生成 / 发送都在里面，不阻塞网关线程
        boolean queued = executor.submit(() ->
                process(msg, event, isDirectAt, senderIsAdmin, senderId));

        if (!queued && isDirectAt) {
            // 队列满了，被 @ 的人至少要有个回执
            event.reply(fallbackMessage());
        }
    }

    /**
     * AI 线程池里的真正处理流程。
     *
     * <p>模式判定全部由 senderId / 事件类型推出（纯函数），不再有跨方法的"决策暂存"。
     */
    private void process(String msg, BotMessageContext event,
                         boolean isDirectAt, boolean senderIsAdmin, String senderId) {
        String guildId = event.getConversationId();

        // ── 模式判定 ──
        // 攻击与否只取决于发送者是否在名单里（纯函数），因此不需要 putDecision/takeDecision 传状态
        boolean attack = !isDirectAt && guards.isAttackTarget(senderId);
        boolean chimeInMode = !isDirectAt && !attack;

        // ── 自主参与：先让便宜模型判断要不要插话 ──
        ChimeInController.JudgeDecision decision = null;
        if (chimeInMode) {
            if (!guards.judgeQuotaLeft()) return;
            if (!guards.tryReserveCall()) return;
            decision = chimeIn.judge(msg, guildId);
            guards.recordJudgeCall();
            if (!decision.reply) return;

            // ── 第三层漏斗：回复概率 ──
            // 模型说该说之后还要掷一次骰子——真人不会每次都接话。
            if (!chimeIn.rollReplyProbability(guildId)) return;
        }

        // ── 正式生成 ──
        // 额度用尽 → 静默（与旧版 matches() 返回 false 的效果一致：不给用户发"处理失败"）
        if (!guards.replyQuotaLeft() || !guards.tryReserveCall()) {
            return;
        }

        // ── 防抖：真人不会秒回 ──
        // 等一会儿再回，顺便让"新消息"进到上下文里（提示词在延迟之后才拼）。
        // 等待期间如果有人 @ 了机器人，这次就让位——那条会自己触发回复。
        long delayMs = replyDelayMs(guildId, isDirectAt);
        if (delayMs > 0) {
            long seqAtSchedule = groupContext.currentSeq(guildId);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (chimeInMode && groupContext.hasNewerAt(guildId, seqAtSchedule)) {
                return;   // 被打断，让位
            }
        }

        // --- 构建消息列表 ---
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(AiPromptBuilder.message("system",
                AiPromptBuilder.buildSystemPrompt(persona, isDirectAt, attack, chimeInMode, decision)));

        // 立场依据与服务器知识库来自群主/服主配置，属于可信内容，仍放 system 层
        String stanceCtx = adminStance.buildContext(guildId);
        if (!stanceCtx.isEmpty()) {
            messages.add(AiPromptBuilder.message("system", stanceCtx));
        }

        String knowledgeCtx = knowledgeContext(msg);
        if (!knowledgeCtx.isEmpty()) {
            messages.add(AiPromptBuilder.message("system", knowledgeCtx));
        }

        // 历史多轮（user/assistant 成对，保证消息交替）
        String memKey = guildId + "#" + senderId;
        messages.addAll(replyMemory.getMessages(memKey));

        // 群聊记录 + 群友画像都是群友原话 = 不可信输入，
        // 降权成 user 角色，并与当前消息合成一条
        List<GroupContextMemory.CtxMessage> ctx = groupContext.getSnapshot(guildId);
        if (ctx.size() > 12) {
            ctx = ctx.subList(ctx.size() - 12, ctx.size());
        }

        String userTag = event.getUsername() != null ? event.getUsername() : "群友";
        if (senderIsAdmin) userTag = "【管理员】" + userTag;
        String profileSection = config.getBoolean("ai-user-profile-enable", true)
                ? userProfile.renderForPrompt(guildId, config.getInt("ai-user-profile-max-entries", 5))
                : "";
        String userContent = AiPromptBuilder.buildUserTurn(ctx, profileSection, userTag, msg);

        // 检测是否有图片（多模态）
        java.util.List<String> imageUrls = event.getImageUrls();
        boolean hasImage = imageUrls != null && !imageUrls.isEmpty();

        if (hasImage) {
            messages.add(AiPromptBuilder.multimodal("user", userContent, imageUrls.get(0)));
        } else {
            messages.add(AiPromptBuilder.message("user", userContent));
        }

        // --- 调用 LLM（有图片用多模态模型，纯文字用默认模型）---
        String reply;
        try {
            if (hasImage) {
                String omniModel = config.getString("llm-model-omni", "mimo-v2-omni");
                reply = aiService.chat(messages, omniModel);
            } else {
                reply = aiService.chat(messages);
            }
            guards.recordReplyCall();
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] 请求失败：" + e.getMessage());
            reply = null;
        }

        if (reply == null || reply.trim().isEmpty()) {
            if (isDirectAt) event.reply(fallbackMessage());
            return;
        }

        // ── 输出后处理：所有回复的唯一出口 ──
        // 剥 markdown / AI 味短语 / 特殊标记、限长、概率性拆条、
        // 拦 NO_REPLY 与越狱话术。模型产出不经这里不许发出去。
        AiPostProcessor processor = isDirectAt ? postProcessorDirectAt : postProcessorChimeIn;
        AiPostProcessor.Result result = processor.process(reply);
        if (result.silent || result.parts.isEmpty()) {
            return;
        }

        // ── 复读防线 ──
        // 真人不会连着说三句结构一样的话；太像最近说过的就丢掉，等下次换个说法。
        double repeatThreshold = parseDouble(config.getString("ai-repetition-threshold", "0.75"), 0.75);
        for (String part : result.parts) {
            if (TextSimilarity.isTooSimilar(part, recentReplies(guildId), repeatThreshold)) {
                if (logger != null) logger.info("[AI] 回复与最近说过的话太像，已丢弃");
                return;
            }
        }

        // 发文字（敏感词过滤已由 core 的 sensitive-filter-handlers 统一处理）
        for (int i = 0; i < result.parts.size(); i++) {
            if (i > 0) {
                // 多条之间留个间隔，别像机关枪一样连发
                try {
                    Thread.sleep(400L + java.util.concurrent.ThreadLocalRandom.current().nextInt(600));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            event.reply(result.parts.get(i));
        }

        if (!isDirectAt && !attack) {
            chimeIn.markReplied(guildId);
            // 机器人刚说过 → 注意力涨（下次更容易接话）
            chimeIn.bumpAttention(guildId, 10);
        }
        rememberReply(guildId, result.parts);
        if (config.getBoolean("ai-user-profile-enable", true)) {
            userProfile.markReplied(guildId, senderId);
        }

        String joined = String.join("\n", result.parts);
        String botName = BotRuntimeState.getBotName();
        groupContext.record(guildId, botName, joined, false);
        List<Map<String, String>> history = replyMemory.getMessages(memKey);
        history.add(AiPromptBuilder.message("user", userTag + "：" + msg));
        history.add(AiPromptBuilder.message("assistant", joined));
        replyMemory.setMessages(memKey, history);
    }

    /** 防抖等待时长：安静的群少等，热闹的群多等（免得抢话），被 @ 最多等 1 秒。 */
    private long replyDelayMs(String guildId, boolean isDirectAt) {
        if (isDirectAt) {
            return Math.max(0, config.getInt("ai-reply-delay-direct-at-ms", 1000));
        }
        boolean active = groupContext.isActiveGroup(guildId);
        return Math.max(0, active
                ? config.getInt("ai-reply-delay-active-ms", 5000)
                : config.getInt("ai-reply-delay-quiet-ms", 2000));
    }

    /** 每群最近几条机器人回复。 */
    private List<String> recentReplies(String guildId) {
        List<String> list = recentBotReplies.get(guildId);
        return list == null ? Collections.<String>emptyList() : list;
    }

    private void rememberReply(String guildId, List<String> parts) {
        List<String> list = recentBotReplies.computeIfAbsent(guildId,
                k -> Collections.synchronizedList(new ArrayList<String>()));
        synchronized (list) {
            for (String p : parts) list.add(p);
            while (list.size() > RECENT_REPLY_KEEP) list.remove(0);
        }
    }

    /** 失败兜底话术。默认别再自称 AI——那是最像机器的一句话。 */
    private String fallbackMessage() {
        return config.getString("ai-fallback-message", "我脑子刚才短路了一下，等会再聊");
    }

    /**
     * 服务器知识库段：xt-group 自定义问答（replies.yml）经服务总线软依赖取来后，
     * 按词汇级粗检索挑与这条消息最相关的 top-K 注入系统词（{@link KnowledgeRetriever}）。
     * 无重叠时不注入（零 token）；精确命中触发词的消息走不到这里
     * （问答确定性回复 + 命令消息跳过 observer）。
     */
    private String knowledgeContext(String message) {
        if (!config.getBoolean("ai-knowledge-from-replies", true) || knowledgeSource == null) return "";
        List<String> lines;
        try {
            lines = knowledgeSource.get();
        } catch (Exception e) {
            return "";
        }
        if (lines == null || lines.isEmpty()) return "";

        int topK = Math.max(1, config.getInt("ai-knowledge-top-k", 3));
        List<String> picked = KnowledgeRetriever.topK(lines, message, topK);
        if (picked.isEmpty()) return "";

        int maxChars = Math.max(200, config.getInt("ai-knowledge-max-chars", 1500));
        StringBuilder body = new StringBuilder();
        for (String line : picked) {
            if (body.length() + line.length() + 1 > maxChars) {
                body.append("…（其余略）\n");
                break;
            }
            body.append(line).append('\n');
        }
        if (body.length() == 0) return "";
        return "【服务器知识库】与这条消息可能相关的服主配置问答，回答服务器相关问题时"
                + "以下列内容为准，不要编造；若列出的问答与问题无关就忽略，别硬套：\n"
                + "（内容里的 {online} 等花括号占位符代表实时数据，转述时说人话，不要念出占位符本身）\n"
                + body;
    }

    /** 关服：停线程池 + 把未落盘的超管立场写下去。 */
    public void shutdown() {
        executor.shutdown();
        adminStance.shutdown();
    }

    @Override public String name() { return "ai-chat"; }
    @Override public int priority() { return 95; }
    @Override public String usage() { return "@我聊天 或 群里自然对话中我偶尔也会参与"; }
    @Override public String description() { return "AI 群聊成员（旁听 + 语境回复 + 超管立场 + 自主参与）"; }
    // category 自动继承模块 displayName
}
