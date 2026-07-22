package org.windy.xingtubot.ext.xtgroup.handler;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.event.BotMessageType;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.runtime.XingtuBotServiceImpl;

import java.util.Set;

/**
 * 新成员加入群时的欢迎消息。
 *
 * <p>监听 {@code GROUP_MEMBER_ADD} 事件，通过 event_id 被动回复欢迎消息。
 * 欢迎文案可通过 config 的 {@code welcome-message} 自定义，支持 {bot} 占位符。
 *
 * <p>需要在 QQ 开放平台回调配置中勾选「群成员增加」事件订阅。
 */
public class WelcomeHandler implements BotMessageHandler {

    private static final String DEFAULT_WELCOME = "欢迎新成员！我是 {bot}，有什么需要帮忙的随时 @我~";

    private final String welcomeMessage;
    private final Set<String> allowedGroups;

    public WelcomeHandler(BotConfig config, Set<String> allowedGroups) {
        this.welcomeMessage = config.getStringResolved("welcome-message", DEFAULT_WELCOME);
        this.allowedGroups = allowedGroups;
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        if (event.getMessageType() != BotMessageType.MEMBER_ADD) return false;
        return isGroupAllowed(event.getConversationId());
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String text = welcomeMessage;
        if (text == null || text.isEmpty()) return;
        text = text.replace("{bot}", XingtuBotServiceImpl.runtime().getBotName());
        event.replyMarkdown(text, null);
    }

    private boolean isGroupAllowed(String groupOpenid) {
        if (allowedGroups == null || allowedGroups.isEmpty() || allowedGroups.contains("*")) return true;
        return groupOpenid != null && allowedGroups.contains(groupOpenid);
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
