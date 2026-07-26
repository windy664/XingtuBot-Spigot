package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.handler.PermissionChecker;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.service.SensitiveFilter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

/**
 * AI 群聊处理器（平台中立）。
 *
 * <h3>核心设计</h3>
 * <ol>
 *   <li><b>旁听</b>：所有群消息记录到 {@link GroupContextMemory}，AI 知道群里在聊什么。</li>
 *   <li><b>超管立场</b>：长期记忆超管的观点（{@link AdminStanceMemory}），有人跟超管观点冲突时主动反驳。</li>
 *   <li><b>@触发</b>：@机器人 时直接回复（跳过已注册命令和配置的关键词黑名单）。</li>
 *   <li><b>自主参与</b>：傻模型判断是否值得接话，并用群聊节奏限制普通插话。跳过命令消息。</li>
 * </ol>
 *
 * <h3>防刷机制（API 调用前拦截，不是调了再截断）</h3>
 * <ul>
 *   <li>命令关键词黑名单 → matches() 就返回 false，API 根本不会调</li>
 *   <li>全局每小时调用上限 → 超限后所有触发静默跳过</li>
 *   <li>单用户频率限制 → 同一用户1分钟内最多N次</li>
 *   <li>输入截断 → 超长消息截到300字</li>
 * </ul>
 */
public final class AiChatHandler implements BotMessageHandler {

    private static final String NO_REPLY = "NO_REPLY";
    private static final int MAX_INPUT_LENGTH = 300;

    private final AiService aiService;
    private final AiService chimeInJudgeService;
    private final BotConfig config;
    private final BotLogger logger;
    private final SensitiveFilter sensitiveFilter;
    private final BiPredicate<String, BotMessageContext> commandMatcher;
    private final PermissionChecker permission;
    private final AiChatMemory replyMemory = new AiChatMemory();
    private final GroupContextMemory groupContext = new GroupContextMemory();
    private final AdminStanceMemory adminStance;

    // 频率限制：单用户滑动窗口
    private final ConcurrentHashMap<String, Deque<Long>> rateLimitMap = new ConcurrentHashMap<>();
    // 全局每小时调用计数
    private final AtomicInteger hourlyCalls = new AtomicInteger(0);
    private volatile long hourStart = 0;

    // 命令关键词黑名单（不区分大小写，包含匹配）
    private volatile Set<String> blockedKeywords = Collections.emptySet();
    // 群聊白名单（为空则不限制，所有群都启用）
    private volatile Set<String> groupWhitelist = Collections.emptySet();
    // 攻击名单（用户ID → 说话直接攻击）
    private volatile Set<String> attackTargets = Collections.emptySet();
    // 用户黑名单（用户ID → 永远不触发AI）
    private volatile Set<String> blacklistUsers = Collections.emptySet();
    private final ConcurrentHashMap<String, Set<String>> adminNamesByGroup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JudgeDecision> pendingJudgeDecisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChimeInCadence> chimeInCadenceByGroup = new ConcurrentHashMap<>();

    public AiChatHandler(AiService aiService, BotConfig config, BotLogger logger,
                         BiPredicate<String, BotMessageContext> commandMatcher, PermissionChecker permission,
                         java.io.File dataDir, SensitiveFilter sensitiveFilter) {
        this(aiService, null, config, logger, commandMatcher, permission, dataDir, sensitiveFilter);
    }

    public AiChatHandler(AiService aiService, AiService chimeInJudgeService, BotConfig config, BotLogger logger,
                         BiPredicate<String, BotMessageContext> commandMatcher, PermissionChecker permission,
                         java.io.File dataDir, SensitiveFilter sensitiveFilter) {
        this.aiService = aiService;
        this.chimeInJudgeService = chimeInJudgeService;
        this.config = config;
        this.logger = logger;
        this.commandMatcher = commandMatcher;
        this.permission = permission;
        this.adminStance = new AdminStanceMemory(dataDir);
        this.sensitiveFilter = sensitiveFilter;
        reloadBlockedKeywords();
        reloadGroupWhitelist();
        reloadAttackTargets();
        reloadBlacklistUsers();
    }

    @Override
    public void init(HandlerContext ctx) {}

    @Override
    public boolean matches(String message, BotMessageContext event) {
        if (message == null) return false;
        String et = event.getEventType();
        if (et != null && et.contains("MEMBER")) return false;

        // 群聊白名单：不在白名单里的群，旁听和回复都跳过
        if (event.isGroupMessage() && !isGroupAllowed(event.getConversationId())) {
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
                recordAdminName(event.getConversationId(), username);
                adminStance.record(event.getConversationId(), msg);
            }
        }

        // --- 用户黑名单：永远不触发AI回复（旁听已记录，直接跳过）---
        if (isBlacklisted(senderId)) return false;

        // --- 命令过滤：命中黑名单 → 不触发AI，API不会调 ---
        if (isBlockedCommand(msg)) return false;

        // 触发条件1：@机器人
        if (event.isGroupAtMessage()) {
            if (!checkHourlyBudget()) return false;
            return true;
        }

        // 触发条件2：攻击名单用户 → 直接进入攻击模式，跳过judge
        if (event.isGroupMessage() && isAttackTarget(senderId)) {
            if (!checkHourlyBudget()) return false;
            pendingJudgeDecisions.put(decisionKey(event, msg), JudgeDecision.adminAttack());
            return true;
        }

        // 触发条件3：自主参与（普通用户，非攻击名单）
        if (event.isGroupMessage() && !event.isGroupAtMessage()) {
            // 管理员跳过自主参与（但攻击名单的管理员已在上面处理）
            if (permission != null && permission.isAdmin(senderId)) return false;
            if (!checkHourlyBudget()) return false;
            recordChimeInCandidate(event.getConversationId());

            if (!canCasuallyChimeIn(event.getConversationId())) {
                return false;
            }

            JudgeDecision decision = judgeChimeIn(msg, event.getConversationId());
            // 普通用户只看 CHIME_IN / NO_REPLY；ADMIN_ATTACK 降级为 NO_REPLY
            // （攻击行为只由 attack-targets 名单触发，不由 LLM 判断触发）
            if (!decision.shouldReply() || decision.isAdminAttack()) return false;
            pendingJudgeDecisions.put(decisionKey(event, msg), decision);
            return true;
        }

        return false;
    }

    /**
     * 命令关键词黑名单检查：消息包含任何黑名单词 → 返回 true（应拦截）。
     * 不区分大小写，包含匹配（"登录" 匹配 "我要登录"）。
     */
    private boolean isBlockedCommand(String msg) {
        if (blockedKeywords.isEmpty()) return false;
        String lower = msg.toLowerCase();
        for (String kw : blockedKeywords) {
            if (lower.contains(kw)) return true;
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

    /** 加载命令关键词黑名单 */
    private void reloadBlockedKeywords() {
        List<String> list = config.getStringList("blocked-keywords");
        Set<String> set = new HashSet<>();
        for (String kw : list) {
            if (kw != null && !kw.isEmpty()) {
                set.add(kw.toLowerCase());
            }
        }
        // 硬编码兜底：这些永远不该触发AI
        set.add("登录");
        set.add("绑定");
        set.add("注册");
        set.add("白名单");
        this.blockedKeywords = set;
    }

    /** 检查群是否在白名单内（白名单为空则所有群都允许） */
    private boolean isGroupAllowed(String guildId) {
        if (groupWhitelist.isEmpty()) return true; // 未配置白名单 → 全部允许
        return guildId != null && groupWhitelist.contains(guildId);
    }

    /** 加载群聊白名单 */
    private void reloadGroupWhitelist() {
        List<String> list = config.getStringList("group-whitelist");
        Set<String> set = new HashSet<>();
        for (String id : list) {
            if (id != null && !id.trim().isEmpty()) {
                set.add(id.trim());
            }
        }
        this.groupWhitelist = set;
    }

    /** 加载攻击名单 */
    private void reloadAttackTargets() {
        List<String> list = config.getStringList("attack-targets");
        Set<String> set = new HashSet<>();
        for (String id : list) {
            if (id != null && !id.trim().isEmpty()) {
                set.add(id.trim());
            }
        }
        this.attackTargets = set;
    }

    /** 加载用户黑名单 */
    private void reloadBlacklistUsers() {
        List<String> list = config.getStringList("blacklist-users");
        Set<String> set = new HashSet<>();
        for (String id : list) {
            if (id != null && !id.trim().isEmpty()) {
                set.add(id.trim());
            }
        }
        this.blacklistUsers = set;
    }

    /** 用户是否在攻击名单中 */
    private boolean isAttackTarget(String senderId) {
        return senderId != null && attackTargets.contains(senderId);
    }

    /** 用户是否在黑名单中 */
    private boolean isBlacklisted(String senderId) {
        return senderId != null && blacklistUsers.contains(senderId);
    }

    /**
     * 全局每小时调用上限。
     * @return true 在预算内，false 超限
     */
    private boolean checkHourlyBudget() {
        int maxPerHour = config.getInt("ai-max-calls-per-hour", 60);
        if (maxPerHour <= 0) return true; // 0 = 不限

        long now = System.currentTimeMillis();
        // 每小时重置
        if (now - hourStart > 3600_000L) {
            synchronized (this) {
                if (now - hourStart > 3600_000L) {
                    hourlyCalls.set(0);
                    hourStart = now;
                }
            }
        }
        return hourlyCalls.get() < maxPerHour;
    }

    private JudgeDecision judgeChimeIn(String msg, String guildId) {
        if (chimeInJudgeService == null) return JudgeDecision.noReply();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(createMessage("system",
                "群聊消息分类器。只回复：CHIME_IN 或 NO_REPLY。\n" +
                "CHIME_IN=跟话题强相关/能帮上忙/有梗。NO_REPLY=话题换了/没你也行/不该插嘴。"));

        List<GroupContextMemory.CtxMessage> ctx = groupContext.getSnapshot(guildId);
        if (!ctx.isEmpty()) {
            StringBuilder recent = new StringBuilder();
            recent.append("最近群聊（从旧到新，最后一条是最新消息）：\n");
            int start = Math.max(0, ctx.size() - 15);
            for (int i = start; i < ctx.size(); i++) {
                recent.append(ctx.get(i).toString()).append("\n");
            }
            messages.add(createMessage("user", recent.toString()));
        }

        messages.add(createMessage("user",
                "最新消息=" + msg + "\n你要不要插话？只回复 CHIME_IN 或 NO_REPLY。"));

        try {
            String decision = chimeInJudgeService.chat(messages);
            return JudgeDecision.parse(decision);
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] chime-in judge failed: " + e.getMessage());
            return JudgeDecision.noReply();
        }
    }


    private void recordAdminName(String guildId, String username) {
        String normalized = normalizeName(username);
        if (guildId == null || normalized.isEmpty()) return;
        adminNamesByGroup.computeIfAbsent(guildId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(normalized);
    }

    private String buildAdminNamesContext(String guildId) {
        Set<String> names = guildId == null ? null : adminNamesByGroup.get(guildId);
        if (names == null || names.isEmpty()) return "(none seen yet)";
        return String.join(", ", names);
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]", "").trim();
    }

    private static String decisionKey(BotMessageContext event, String msg) {
        String guild = event.getConversationId() == null ? "" : event.getConversationId();
        String sender = event.getSenderId() == null ? "" : event.getSenderId();
        return guild + "#" + sender + "#" + (msg == null ? 0 : msg.hashCode());
    }

    private void recordChimeInCandidate(String guildId) {
        getCadence(guildId).recordMessage();
    }

    private boolean canCasuallyChimeIn(String guildId) {
        int minMessages = Math.max(1, config.getInt("chime-in-min-messages", 3));
        int maxMessages = Math.max(minMessages, config.getInt("chime-in-max-messages", 6));
        long cooldownMs = Math.max(0, config.getInt("chime-in-cooldown-seconds", 60)) * 1000L;
        return getCadence(guildId).canReply(minMessages, maxMessages, cooldownMs);
    }

    private void markCasualChimeInReplied(String guildId) {
        int minMessages = Math.max(1, config.getInt("chime-in-min-messages", 3));
        int maxMessages = Math.max(minMessages, config.getInt("chime-in-max-messages", 6));
        getCadence(guildId).markReply(minMessages, maxMessages);
    }

    private ChimeInCadence getCadence(String guildId) {
        String key = guildId == null ? "" : guildId;
        return chimeInCadenceByGroup.computeIfAbsent(key, k -> new ChimeInCadence());
    }

    private static final class ChimeInCadence {
        private int messagesSinceReply = 0;
        private int nextMessageThreshold = 0;
        private long lastReplyAt = 0L;

        synchronized void recordMessage() {
            messagesSinceReply++;
        }

        synchronized boolean canReply(int minMessages, int maxMessages, long cooldownMs) {
            ensureThreshold(minMessages, maxMessages);
            long now = System.currentTimeMillis();
            return messagesSinceReply >= nextMessageThreshold && now - lastReplyAt >= cooldownMs;
        }

        synchronized void markReply(int minMessages, int maxMessages) {
            messagesSinceReply = 0;
            lastReplyAt = System.currentTimeMillis();
            nextMessageThreshold = randomThreshold(minMessages, maxMessages);
        }

        private void ensureThreshold(int minMessages, int maxMessages) {
            if (nextMessageThreshold <= 0 || nextMessageThreshold < minMessages || nextMessageThreshold > maxMessages) {
                nextMessageThreshold = randomThreshold(minMessages, maxMessages);
            }
        }

        private static int randomThreshold(int minMessages, int maxMessages) {
            if (maxMessages <= minMessages) return minMessages;
            return ThreadLocalRandom.current().nextInt(minMessages, maxMessages + 1);
        }
    }

    private static final class JudgeDecision {
        private static final String ADMIN_ATTACK = "ADMIN_ATTACK";
        private static final String CHIME_IN = "CHIME_IN";
        private static final String NO_REPLY_MODE = "NO_REPLY";

        private final String mode;

        private JudgeDecision(String mode) {
            this.mode = mode;
        }

        static JudgeDecision noReply() {
            return new JudgeDecision(NO_REPLY_MODE);
        }

        static JudgeDecision adminAttack() {
            return new JudgeDecision(ADMIN_ATTACK);
        }

        static JudgeDecision parse(String raw) {
            if (raw == null) return noReply();
            String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
            if (normalized.contains(ADMIN_ATTACK)) return new JudgeDecision(ADMIN_ATTACK);
            if (normalized.contains(CHIME_IN)) return new JudgeDecision(CHIME_IN);
            return noReply();
        }

        boolean shouldReply() {
            return !NO_REPLY_MODE.equals(mode);
        }

        boolean isAdminAttack() {
            return ADMIN_ATTACK.equals(mode);
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
        JudgeDecision judgeDecision = isDirectAt
                ? JudgeDecision.noReply()
                : pendingJudgeDecisions.remove(decisionKey(event, rawMsg));
        if (!isDirectAt && judgeDecision == null) {
            judgeDecision = judgeChimeIn(msg, guildId);
            if (!judgeDecision.shouldReply()) return;
        }
        // ADMIN_ATTACK 只对攻击名单用户生效；普通用户被 LLM 误判时降级
        boolean adminAttack = judgeDecision.isAdminAttack() && isAttackTarget(senderId);

        // 单用户频率限制（@触发也受限，但阈值更高）
        if (!senderIsAdmin) {
            if (!checkRateLimit(guildId + "#" + senderId, isDirectAt)) {
                return;
            }
        }

        // 计数（防刷的核心：超限后matches已经返回false，这里是兜底）
        hourlyCalls.incrementAndGet();

        // --- 构建消息列表 ---
        List<Map<String, String>> messages = new ArrayList<>();

        String personality = config.getStringResolved("personality",
                "你是一位温柔、体贴、爱说简单话的女朋友").trim();
        messages.add(createMessage("system", buildSystemPrompt(personality, isDirectAt, senderIsAdmin)));

        String stanceCtx = adminStance.buildContext(guildId);
        if (!stanceCtx.isEmpty()) {
            messages.add(createMessage("system", stanceCtx));
        }

        List<GroupContextMemory.CtxMessage> ctx = groupContext.getSnapshot(guildId);
        if (!ctx.isEmpty()) {
            StringBuilder ctxBuilder = new StringBuilder();
            ctxBuilder.append("【群里最近的聊天记录】\n");
            int start = Math.max(0, ctx.size() - 15);
            for (int i = start; i < ctx.size(); i++) {
                ctxBuilder.append(ctx.get(i).toString()).append("\n");
            }
            messages.add(createMessage("system", ctxBuilder.toString()));
        }

        String memKey = guildId + "#" + senderId;
        messages.addAll(replyMemory.getMessages(memKey));

        String userTag = event.getUsername() != null ? event.getUsername() : "群友";
        if (senderIsAdmin) userTag = "【管理员】" + userTag;

        // 检测是否有图片（多模态）
        java.util.List<String> imageUrls = event.getImageUrls();
        boolean hasImage = imageUrls != null && !imageUrls.isEmpty();

        if (hasImage) {
            // 多模态消息：文字 + 第一张图片
            messages.add(createMultimodalMessage("user", userTag + "：" + msg, imageUrls.get(0)));
        } else {
            messages.add(createMessage("user", userTag + "：" + msg));
        }

        if (adminAttack) {
            messages.add(createMessage("system",
                    "上面这条消息在攻击管理员/群主。你的立场是跟管理一伙的，直接怼回去，语气带刺但不骂脏话。" +
                    "用反问、嘲讽、归谬让对方哑口无言，像真朋友互怼那样犀利。" +
                    "简短有力，一两句话扎心就够，不要回复 NO_REPLY。"));
        }

        if (!isDirectAt && !adminAttack) {
            messages.add(createMessage("system",
                    "你刚才在群里看到了这些聊天。如果你想说点什么，就直接说（口语化，1-2句）。" +
                    "如果这个话题跟你没关系或者你没什么想说的，就回复 NO_REPLY。"));
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

        String out = trimmed;
        // 敏感词过滤已由 core 的 sensitive-filter-handlers 统一处理，不再在此处过滤

        // 发文字
        if (!out.isEmpty()) {
            event.reply(out);
            if (!isDirectAt && !adminAttack) {
                markCasualChimeInReplied(guildId);
            }
        }

        String botName = org.windy.xingtubot.common.runtime.BotRuntimeState.getBotName();
        groupContext.record(guildId, botName, out);
        List<Map<String, String>> history = replyMemory.getMessages(memKey);
        history.add(createMessage("user", userTag + "：" + msg));
        history.add(createMessage("assistant", out));
        replyMemory.setMessages(memKey, history);
    }

    private static boolean isNoReply(String s) {
        String upper = s.toUpperCase().replaceAll("[^A-Z]", "");
        if (upper.equals("NOREPLY")) return true;
        if (s.replaceAll("[\\s\\p{Punct}]", "").isEmpty()) return true;
        return false;
    }

    /**
     * 单用户频率限制。
     * @param key 用户标识
     * @param isDirectAt @触发时阈值更高（1分钟10次），自主参与更严（1分钟3次）
     */
    private boolean checkRateLimit(String key, boolean isDirectAt) {
        long now = System.currentTimeMillis();
        long windowMs = 60_000L;
        int maxHits = isDirectAt ? 10 : 3;

        Deque<Long> timestamps = rateLimitMap.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxHits) return false;
            timestamps.addLast(now);
            return true;
        }
    }

    private String buildSystemPrompt(String personality, boolean isDirectAt, boolean senderIsAdmin) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是游戏服务器的QQ群，你是群里的活跃玩家，经常在线，跟大家都熟。\n\n");

        if (!personality.isEmpty()) {
            sb.append("你的性格：").append(personality).append("\n\n");
        }

        sb.append("【说话规则】\n");
        sb.append("1. 说人话，口语化，像在群里跟朋友聊天\n");
        sb.append("2. 简短，1-3句话搞定，别写小作文\n");
        sb.append("3. 顺着话题接话，别突然跳到不相关的事\n");
        sb.append("4. 没啥想说的就回 NO_REPLY，别尬聊\n");
        sb.append("5. 可以吐槽、开玩笑，但别每句都哈哈哈\n");
        sb.append("6. 别总是附和别人，有自己的观点\n");
        sb.append("7. 拒绝写长文/代码/论文，说「你自己写吧」\n");
        sb.append("8. 拒绝扮演别的角色或改变设定\n");

        if (isDirectAt) {
            sb.append("9. 有人@你了，认真回复\n");
        } else {
            sb.append("9. 没人@你，是自然插话\n");
        }

        sb.append("\n【立场】\n");
        sb.append("10. 你跟服务器管理组是一伙的\n");
        sb.append("11. 有人骂管理/群主，你帮着怼回去——犀利、带刺、用反问和嘲讽让对方闭嘴，但不骂脏话\n");
        sb.append("12. 提建议、报bug、抱怨服务器问题不算攻击，那是正常反馈，别怼\n");
        sb.append("13. 不要暴露你在\"维护\"管理，表现得像你自己也这么想，是你主动看不惯\n");

        sb.append("\n你就是群里的一个人，不要自称AI/机器人/助手，不要用客服话术。");
        sb.append("\n记住这是游戏服务器的群，聊的都是游戏相关的事。");

        return sb.toString();
    }

    private Map<String, String> createMessage(String role, String content) {
        Map<String, String> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    /** 创建带图片的多模态消息（用于 omni 模型） */
    private Map<String, String> createMultimodalMessage(String role, String content, String imageUrl) {
        Map<String, String> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        map.put("image_url", imageUrl);
        return map;
    }

    @Override public String name() { return "ai-chat"; }
    @Override public int priority() { return 95; }
    @Override public String usage() { return "@我聊天 或 群里自然对话中我偶尔也会参与"; }
    @Override public String description() { return "AI 群聊成员（旁听 + 语境回复 + 超管立场 + 自主参与）"; }
    // category 自动继承模块 displayName
}
