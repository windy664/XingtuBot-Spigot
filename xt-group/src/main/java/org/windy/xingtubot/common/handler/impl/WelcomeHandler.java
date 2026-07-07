package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.MessageHandler;

/**
 * 新成员加入群时的欢迎消息。
 *
 * <p>监听 {@code GROUP_MEMBER_ADD} 事件，通过 event_id 被动回复欢迎消息。
 * 欢迎文案可通过 config 的 {@code welcome-message} 自定义，支持 {bot} 占位符。
 *
 * <p>需要在 QQ 开放平台回调配置中勾选「群成员增加」事件订阅。
 */
public class WelcomeHandler implements MessageHandler {

    private static final String DEFAULT_WELCOME = "欢迎新成员！我是 {bot}，有什么需要帮忙的随时 @我~";

    private volatile String welcomeMessage;
    private BotConfig config;

    @Override
    public boolean matches(String message, BotMessageEvent event) {
        return "GROUP_MEMBER_ADD".equals(event.getEventType());
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String text = welcomeMessage;
        if (text == null || text.isEmpty()) return;

        text = text.replace("{bot}", org.windy.xingtubot.common.BotIdentity.getName());

        System.out.println("[Welcome] 收到 " + event.getEventType() + "，发送欢迎消息到 " + event.getGuildId());
        event.replyMarkdown(text, null);
    }

    @Override
    public void init(HandlerContext ctx) {
        this.config = ctx.getConfig();
        this.welcomeMessage = config.getStringResolved("welcome-message", DEFAULT_WELCOME);
    }

    @Override
    public String name() {
        return "welcome";
    }

    @Override
    public int priority() {
        return 5;
    }
}
