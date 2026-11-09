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
import java.util.List;
import java.util.Map;
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
 *   <li><b>自主参与</b>：傻模型判断是否值得接话（{@link ChimeInController}），并用群聊节奏限制普通插话。跳过命令消息。</li>
 * </ol>
 *
 * <h3>防刷机制（API 调用前拦截，不是调了再截断）</h3>
 * <ul>
 *   <li>命令关键词黑名单 → matches() 就返回 false，API 根本不会调（{@link AiGuards}）</li>
 *   <li>全局每小时调用上限 → 超限后所有触发静默跳过</li>
 *   <li>单用户频率限制 → 同一用户1分钟内最多N次</li>
 *   <li>输入截断 → 超长消息截到300字</li>
 * </ul>
 */
public final class AiChatHandler implements BotMessageHandler {

    private static final int MAX_INPUT_LENGTH = 300;

    private final AiService aiService;
    private final BotConfig config;
    private final BotLogger logger;
    private final BiPredicate<String, BotMessageContext> commandMatcher;
    private final PermissionChecker permission;
    private final AiChatMemory replyMemory = new AiChatMemory();
    private final GroupContextMemory groupContext = new GroupContextMemory();
    private final AdminStanceMemory adminStance;
    private final AiGuards guards;
    private final ChimeInController chimeIn;
    // 服务器知识库来源（服务总线懒查询的 Supplier<List<String>>，xt-group 未装时取到 null）
    private final Supplier<List<String>> knowledgeSource;

    public AiChatHandler(AiService aiService, BotConfig config, BotLogger logger,
                         BiPredicate<String, BotMessageContext> commandMatcher, PermissionChecker permission,
                         java.io.File dataDir) {
        this(aiService, null, config, logger, commandMatcher, permission, dataDir, null);
    }

    public AiChatHandler(AiService aiService, AiService chimeInJudgeService, BotConfig config, BotLogger logger,
                         BiPredicate<String, BotMessageContext> commandMatcher, PermissionChecker permission,
                         java.io.File dataDir, Supplier<List<String>> knowledgeSource) {
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

        // 旁听：白名单内的群消息记录到群上下文（不消耗API，只记内存）
        if (event.isGroupMessage()) {
            String username = event.getUsername() != null ? event.getUsername() : "群友";
            groupContext.record(event.getConversationId(), username, msg);

            if (permission != null && permission.isAdmin(senderId)) {
                adminStance.record(event.getConversationId(), msg);
            }
        }

        // --- 用户黑名单：永远不触发AI回复（旁听已记录，直接跳过）---
        if (guards.isBlacklisted(senderId)) return false;

        // --- 命令过滤：命中黑名单 → 不触发AI，API不会调 ---
        if (guards.isBlockedCommand(msg)) return false;

        // 触发条件1：@机器人
        if (event.isGroupAtMessage()) {
            if (!guards.checkHourlyBudget()) return false;
            return true;
        }

        // 触发条件2：攻击名单用户 → 直接进入攻击模式，跳过judge
        if (event.isGroupMessage() && guards.isAttackTarget(senderId)) {
            if (!guards.checkHourlyBudget()) return false;
            chimeIn.putDecision(ChimeInController.decisionKey(event, msg),
                    ChimeInController.JudgeDecision.adminAttack());
            return true;
        }

        // 触发条件3：自主参与（普通用户，非攻击名单）
        if (event.isGroupMessage() && !event.isGroupAtMessage()) {
            // 管理员跳过自主参与（但攻击名单的管理员已在上面处理）
            if (permission != null && permission.isAdmin(senderId)) return false;
            if (!guards.checkHourlyBudget()) return false;
            chimeIn.recordCandidate(event.getConversationId());

            if (!chimeIn.canReply(event.getConversationId())) {
                return false;
            }

            ChimeInController.JudgeDecision decision = chimeIn.judge(msg, event.getConversationId());
            // 普通用户只看 CHIME_IN / NO_REPLY；ADMIN_ATTACK 降级为 NO_REPLY
            // （攻击行为只由 attack-targets 名单触发，不由 LLM 判断触发）
            if (!decision.shouldReply() || decision.isAdminAttack()) return false;
            chimeIn.putDecision(ChimeInController.decisionKey(event, msg), decision);
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
        String rawMsg = message.trim();
        String msg = rawMsg;
        boolean isDirectAt = event.isGroupAtMessage();
        String guildId = event.getConversationId();
        String senderId = event.getSenderId();
        boolean senderIsAdmin = permission != null && permission.isAdmin(senderId);

        // 输入截断
        if (msg.length() > MAX_INPUT_LENGTH) {
            msg = msg.substring(0, MAX_INPUT_LENGTH);
        }
        ChimeInController.JudgeDecision judgeDecision = isDirectAt
                ? ChimeInController.JudgeDecision.noReply()
                : chimeIn.takeDecision(ChimeInController.decisionKey(event, rawMsg));
        if (!isDirectAt && judgeDecision == null) {
            judgeDecision = chimeIn.judge(msg, guildId);
            if (!judgeDecision.shouldReply()) return;
        }
        // ADMIN_ATTACK 只对攻击名单用户生效；普通用户被 LLM 误判时降级
        boolean adminAttack = judgeDecision.isAdminAttack() && guards.isAttackTarget(senderId);

        // 单用户频率限制（@触发也受限，但阈值更高）
        if (!senderIsAdmin && !guards.checkRateLimit(guildId + "#" + senderId, isDirectAt)) {
            return;
        }

        // 计数（防刷的兜底：超限时 matches 已经返回 false，这里只是记数）
        guards.recordCall();

        // --- 构建消息列表 ---
        boolean isChimeIn = !isDirectAt && !adminAttack;
        List<Map<String, String>> messages = new ArrayList<>();

        String personality = config.getStringResolved("personality",
                "你是一位温柔、体贴、爱说简单话的女朋友").trim();
        messages.add(AiPromptBuilder.message("system",
                AiPromptBuilder.buildSystemPrompt(personality, isDirectAt, adminAttack, isChimeIn)));

        String stanceCtx = adminStance.buildContext(guildId);
        if (!stanceCtx.isEmpty()) {
            messages.add(AiPromptBuilder.message("system", stanceCtx));
        }

        String knowledgeCtx = knowledgeContext(msg);
        if (!knowledgeCtx.isEmpty()) {
            messages.add(AiPromptBuilder.message("system", knowledgeCtx));
        }

        List<GroupContextMemory.CtxMessage> ctx = groupContext.getSnapshot(guildId);
        if (!ctx.isEmpty()) {
            StringBuilder ctxBuilder = new StringBuilder();
            ctxBuilder.append("【群聊记录】\n");
            int start = Math.max(0, ctx.size() - 12);
            for (int i = start; i < ctx.size(); i++) {
                ctxBuilder.append(ctx.get(i).toString()).append("\n");
            }
            messages.add(AiPromptBuilder.message("system", ctxBuilder.toString()));
        }

        String memKey = guildId + "#" + senderId;
        messages.addAll(replyMemory.getMessages(memKey));

        String userTag = event.getUsername() != null ? event.getUsername() : "群友";
        if (senderIsAdmin) userTag = "【管理员】" + userTag;

        // 检测是否有图片（多模态）
        java.util.List<String> imageUrls = event.getImageUrls();
        boolean hasImage = imageUrls != null && !imageUrls.isEmpty();

        if (hasImage) {
            messages.add(AiPromptBuilder.multimodal("user", userTag + "：" + msg, imageUrls.get(0)));
        } else {
            messages.add(AiPromptBuilder.message("user", userTag + "：" + msg));
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
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] 请求失败：" + e.getMessage());
            reply = null;
        }

        if (reply == null || reply.trim().isEmpty()) {
            if (isDirectAt) event.reply("AI 无法处理该请求。");
            return;
        }

        String trimmed = reply.trim();
        if (!isDirectAt && isNoReply(trimmed)) return;

        // 发文字（敏感词过滤已由 core 的 sensitive-filter-handlers 统一处理）
        if (!trimmed.isEmpty()) {
            event.reply(trimmed);
            if (!isDirectAt && !adminAttack) {
                chimeIn.markReplied(guildId);
            }
        }

        String botName = BotRuntimeState.getBotName();
        groupContext.record(guildId, botName, trimmed);
        List<Map<String, String>> history = replyMemory.getMessages(memKey);
        history.add(AiPromptBuilder.message("user", userTag + "：" + msg));
        history.add(AiPromptBuilder.message("assistant", trimmed));
        replyMemory.setMessages(memKey, history);
    }

    private static boolean isNoReply(String s) {
        String upper = s.toUpperCase().replaceAll("[^A-Z]", "");
        if (upper.equals("NOREPLY")) return true;
        if (s.replaceAll("[\\s\\p{Punct}]", "").isEmpty()) return true;
        return false;
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

    @Override public String name() { return "ai-chat"; }
    @Override public int priority() { return 95; }
    @Override public String usage() { return "@我聊天 或 群里自然对话中我偶尔也会参与"; }
    @Override public String description() { return "AI 群聊成员（旁听 + 语境回复 + 超管立场 + 自主参与）"; }
    // category 自动继承模块 displayName
}
