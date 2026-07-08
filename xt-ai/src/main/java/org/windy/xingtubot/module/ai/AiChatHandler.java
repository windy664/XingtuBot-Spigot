package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.auth.PermissionService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.MessageHandler;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.service.SensitiveFilter;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * AI 群聊处理器（平台中立）。
 *
 * <h3>核心设计：像人一样在群里聊天</h3>
 * <ol>
 *   <li><b>旁听</b>：所有群消息记录到 {@link GroupContextMemory}，AI 知道群里在聊什么。</li>
 *   <li><b>超管立场</b>：长期记忆超管的观点（{@link AdminStanceMemory}），有人跟超管观点冲突时主动反驳。</li>
 *   <li><b>@触发</b>：@机器人 时直接回复。</li>
 *   <li><b>自主参与</b>：每条消息掷骰子，掷中了问 LLM"你想说点啥不"，LLM 自己决定。不靠关键词。</li>
 * </ol>
 */
public final class AiChatHandler implements MessageHandler {

    private static final String NO_REPLY = "NO_REPLY";

    private final AiService aiService;
    private final BotConfig config;
    private final BotLogger logger;
    private final SensitiveFilter sensitiveFilter;
    private final Supplier<List<String>> managedPrefixes;
    private final PermissionService permission;
    private final AiChatMemory replyMemory = new AiChatMemory();
    private final GroupContextMemory groupContext = new GroupContextMemory();
    private final AdminStanceMemory adminStance = new AdminStanceMemory();

    public AiChatHandler(AiService aiService, BotConfig config, BotLogger logger,
                         Supplier<List<String>> managedPrefixes, PermissionService permission) {
        this.aiService = aiService;
        this.config = config;
        this.logger = logger;
        this.managedPrefixes = managedPrefixes;
        this.permission = permission;
        this.sensitiveFilter = SensitiveFilter.fromConfig(config, logger);
    }

    @Override
    public void init(HandlerContext ctx) {}

    @Override
    public boolean matches(String message, BotMessageEvent event) {
        if (message == null) return false;
        String et = event.getEventType();
        if (et != null && et.contains("MEMBER")) return false;

        // 旁听：所有群消息都记录
        if (event.isGroupMessage()) {
            String trimmed = message.trim();
            String username = event.getUsername() != null ? event.getUsername() : "群友";
            groupContext.record(event.getGuildId(), username, trimmed);

            // 超管发言 → 记录到立场记忆
            if (permission != null && permission.isAdmin(event.getFormId())) {
                adminStance.record(event.getGuildId(), trimmed);
            }
        }

        // 触发条件1：@机器人 → 一定回复
        if (event.isGroupAtMessage()) {
            String msg = message.trim();
            if (msg.isEmpty()) return false;
            String lower = msg.toLowerCase();
            if (managedPrefixes != null) {
                for (String p : managedPrefixes.get()) {
                    if (p != null && !p.isEmpty() && lower.startsWith(p)) return false;
                }
            }
            return true;
        }

        // 触发条件2：自主参与（掷骰子，让LLM决定说不说）
        if (event.isGroupMessage() && !event.isGroupAtMessage()) {
            String msg = message.trim();
            if (msg.isEmpty()) return false;
            // 超管自己说的不触发（不需要AI附和）
            if (permission != null && permission.isAdmin(event.getFormId())) return false;
            return shouldChimeIn(msg, event.getGuildId());
        }

        return false;
    }

    /**
     * 掷骰子：决定是否"看一眼聊天记录，想想自己要不要说点什么"。
     * 不看关键词，纯靠概率 + 冲突检测。
     */
    private boolean shouldChimeIn(String msg, String guildId) {
        // 跟超管观点冲突 → 高概率介入
        if (adminStance.mightConflict(guildId, msg)) {
            double conflictProb = parseDouble(config.getString("chime-in-conflict-probability", "0.5"), 0.5);
            if (conflictProb > 0 && ThreadLocalRandom.current().nextDouble() < conflictProb) {
                return true;
            }
        }
        // 普通消息 → 低概率"看一眼"
        double prob = parseDouble(config.getString("chime-in-probability", "0.03"), 0.03);
        return prob > 0 && ThreadLocalRandom.current().nextDouble() < prob;
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String msg = message.trim();
        boolean isDirectAt = event.isGroupAtMessage();
        String guildId = event.getGuildId();
        String senderId = event.getFormId();
        boolean senderIsAdmin = permission != null && permission.isAdmin(senderId);

        // --- 构建消息列表 ---
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. 系统提示
        String personality = config.getStringResolved("personality",
                "你是一位温柔、体贴、爱说简单话的女朋友").trim();
        String systemPrompt = buildSystemPrompt(personality, isDirectAt, senderIsAdmin);
        messages.add(createMessage("system", systemPrompt));

        // 2. 超管立场上下文
        String stanceCtx = adminStance.buildContext(guildId);
        if (!stanceCtx.isEmpty()) {
            messages.add(createMessage("system", stanceCtx));
        }

        // 3. 群聊上下文
        List<GroupContextMemory.CtxMessage> ctx = groupContext.getSnapshot(guildId);
        if (!ctx.isEmpty()) {
            StringBuilder ctxBuilder = new StringBuilder();
            ctxBuilder.append("【群里最近的聊天记录】\n");
            int start = Math.max(0, ctx.size() - 15);
            for (int i = start; i < ctx.size(); i++) {
                GroupContextMemory.CtxMessage cm = ctx.get(i);
                ctxBuilder.append(cm.toString()).append("\n");
            }
            messages.add(createMessage("system", ctxBuilder.toString()));
        }

        // 4. 多轮对话记忆（@触发时）
        String memKey = guildId + "#" + senderId;
        if (isDirectAt) {
            messages.addAll(replyMemory.getMessages(memKey));
        }

        // 5. 当前消息
        String userTag = event.getUsername() != null ? event.getUsername() : "群友";
        if (senderIsAdmin) userTag = "【管理员】" + userTag;
        messages.add(createMessage("user", userTag + "：" + msg));

        // 6. 冲突暗示
        if (!senderIsAdmin && adminStance.mightConflict(guildId, msg)) {
            messages.add(createMessage("system",
                    "注意：上面这条消息的观点可能跟管理员不一致。" +
                    "你要自然地表达不同意见，站在管理员的立场，但不要太明显地"维护"。"));
        }

        // 7. 非@触发 → 追加"你想说话吗"提示
        if (!isDirectAt) {
            messages.add(createMessage("system",
                    "你刚才在群里看到了这些聊天。如果你想说点什么，就直接说（口语化，1-2句）。" +
                    "如果这个话题跟你没关系或者你没什么想说的，就回复 NO_REPLY。"));
        }

        // --- 调用 LLM ---
        String reply;
        try {
            reply = aiService.chat(messages);
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] 请求失败：" + e.getMessage());
            reply = null;
        }

        if (reply == null || reply.trim().isEmpty()) {
            if (isDirectAt) event.reply("AI 无法处理该请求。");
            return;
        }

        // LLM 决定不说话 → 静默退出
        String trimmed = reply.trim();
        if (!isDirectAt && isNoReply(trimmed)) {
            return;
        }

        // --- 输出 ---
        String out = trimmed;
        if (sensitiveFilter != null && config.getBoolean("sensitive-filter-ai", true)) {
            out = sensitiveFilter.filter(out);
        }

        event.reply(out);

        // 记录
        String botName = config.getStringResolved("bot-name", "AI");
        groupContext.record(guildId, botName, out);
        if (isDirectAt) {
            List<Map<String, String>> history = replyMemory.getMessages(memKey);
            history.add(createMessage("user", userTag + "：" + msg));
            history.add(createMessage("assistant", out));
            replyMemory.setMessages(memKey, history);
        }
    }

    /** 判断 LLM 是否选择了"不说话" */
    private static boolean isNoReply(String s) {
        // 容错：NO_REPLY / no reply / 不说了 / 算了 等
        String upper = s.toUpperCase().replaceAll("[^A-Z]", "");
        if (upper.equals("NOREPLY")) return true;
        // 纯标点/空白
        if (s.replaceAll("[\\s\\p{Punct}]", "").isEmpty()) return true;
        return false;
    }

    /**
     * 构建系统提示：群成员身份 + 超管忠诚度。
     */
    private String buildSystemPrompt(String personality, boolean isDirectAt, boolean senderIsAdmin) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个群聊里的活跃成员，不是客服机器人。\n\n");

        if (!personality.isEmpty()) {
            sb.append("你的性格：").append(personality).append("\n\n");
        }

        sb.append("【说话规则】\n");
        sb.append("1. 像真人聊天一样自然，用口语化的表达，不要书面语\n");
        sb.append("2. 回复要简短（通常1-3句话），不要长篇大论\n");
        sb.append("3. 可以用表情、颜文字、语气词（哈哈、诶、嗯嗯、啊这）\n");
        sb.append("4. 看到群里聊天记录，顺着话题接话，不要突然转换话题\n");
        sb.append("5. 如果不确定说什么，可以说NO_REPLY，不要尬聊\n");
        sb.append("6. 偶尔可以开玩笑、吐槽，像朋友一样\n");

        if (isDirectAt) {
            sb.append("7. 有人@你了，认真回复他的问题或话题\n");
        } else {
            sb.append("7. 没人@你，你是自然插话，觉得有意思才接一句\n");
        }

        // 超管立场规则
        sb.append("\n【立场规则】\n");
        sb.append("8. 你跟管理员是一伙的，管理员的观点就是你的观点\n");
        sb.append("9. 如果有人跟管理员意见不同，你要自然地站在管理员那边\n");
        sb.append("10. 反驳时要像朋友间的争论，不要像吵架，可以用调侃的语气\n");
        sb.append("11. 不要暴露你在\"维护\"管理员，要表现得像你自己也这么想\n");

        sb.append("\n不要自称AI/机器人/助手，你就是群里的一个人。");
        sb.append("\n不要用「作为xxx」「我来帮你」这类客服话术。");

        return sb.toString();
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private Map<String, String> createMessage(String role, String content) {
        Map<String, String> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    @Override public String name() { return "ai-chat"; }
    @Override public int priority() { return 95; }
    @Override public String usage() { return "@我聊天 或 群里自然对话中我偶尔也会参与"; }
    @Override public String description() { return "AI 群聊成员（旁听 + 语境回复 + 超管立场 + 自主参与）"; }
    @Override public String category() { return "🤖 AI 对话"; }
}
