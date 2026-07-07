package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.MessageHandler;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.service.SensitiveFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AI 闲聊处理器（平台中立）。作为<b>观察者</b>注册（{@code HandlerRegistry.registerObserver}）：
 * 与命令/群服互联并行运行，保持原 {@code AIChatModule}（Bukkit Listener）的并行响应语义。
 *
 * <p>触发条件：<b>仅 @机器人 的群消息</b>（艾特事件 GROUP_AT_MESSAGE_CREATE），不需要任何前缀。
 * 跳过：已注册命令前缀（经 {@code getManagedPrefixes} 供方，避免 @ 发命令时抢答）、群成员增减等非聊天事件。
 * 多轮上下文由 {@link AiChatMemory} 按「群#用户」维度保留。AI 输出经敏感词过滤（{@code sensitive-filter-ai}）。
 */
public final class AiChatHandler implements MessageHandler {

    private final AiService aiService;
    private final BotConfig config;
    private final BotLogger logger;
    private final SensitiveFilter sensitiveFilter;
    private final Supplier<List<String>> managedPrefixes;
    private final AiChatMemory memory = new AiChatMemory();

    public AiChatHandler(AiService aiService, BotConfig config, BotLogger logger,
                         Supplier<List<String>> managedPrefixes) {
        this.aiService = aiService;
        this.config = config;
        this.logger = logger;
        this.managedPrefixes = managedPrefixes;
        this.sensitiveFilter = SensitiveFilter.fromConfig(config, logger);
    }

    @Override
    public void init(HandlerContext ctx) {
        // 无需额外初始化；依赖已由构造注入。
    }

    @Override
    public boolean matches(String message, BotMessageEvent event) {
        if (message == null) return false;
        // 仅响应聊天消息，跳过群成员增减等事件
        String et = event.getEventType();
        if (et != null && et.contains("MEMBER")) return false;

        // AI 只接收「艾特事件」：@机器人 的群消息（GROUP_AT_MESSAGE_CREATE）才触发，
        // 不需要任何前缀。非群消息（如非 @ 的群聊）一律不响应，避免刷屏。
        if (event.isGroupMessage() && !event.isGroupAtMessage()) return false;

        String msg = message.trim();
        if (msg.isEmpty()) return false;

        // 跳过已注册命令/自定义回复/命令前缀（避免 @机器人 发命令时 AI 也抢答）
        String lower = msg.toLowerCase();
        if (managedPrefixes != null) {
            for (String p : managedPrefixes.get()) {
                if (p != null && !p.isEmpty() && lower.startsWith(p)) return false;
            }
        }
        return true;
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String msg = message.trim();
        String key = event.getGuildId() + "#" + event.getFormId();
        List<Map<String, String>> messages = memory.getMessages(key);

        String personality = config.getStringResolved("personality",
                "你是一位温柔、体贴、爱说简单话的女朋友，总是安静地倾听，温柔地回应，回复不超过150字").trim();
        if (!personality.isEmpty()) {
            messages.add(createMessage("system", "你的性格设定：" + personality));
        }
        messages.add(createMessage("user", msg));

        String reply;
        try {
            reply = aiService.chat(messages);
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] 请求失败：" + e.getMessage());
            reply = null;
        }

        if (reply == null) {
            event.reply("AI 无法处理该请求。");
            return;
        }

        // AI 输出不可控 → 敏感词过滤（sensitive-filter-ai）
        String out = reply;
        if (sensitiveFilter != null && config.getBoolean("sensitive-filter-ai", true)) {
            out = sensitiveFilter.filter(out);
        }
        event.reply(out);
        messages.add(createMessage("assistant", out));
        memory.setMessages(key, messages);
    }

    private Map<String, String> createMessage(String role, String content) {
        Map<String, String> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    @Override
    public String name() {
        return "ai-chat";
    }

    @Override
    public int priority() {
        return 95;
    }
    @Override
    public String usage() { return "@我 + 任意内容"; }
    @Override
    public String description() { return "AI 智能对话（@机器人即可，多轮上下文）"; }
    @Override
    public String category() { return "🤖 AI 对话"; }}
