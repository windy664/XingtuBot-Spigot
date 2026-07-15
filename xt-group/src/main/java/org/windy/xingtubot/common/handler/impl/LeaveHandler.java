package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;

import java.util.Set;

/**
 * 成员离开群时的消息。
 *
 * <p>监听 {@code GROUP_MEMBER_REMOVE} 事件，通过 event_id 被动回复。
 * 文案可通过 config 的 {@code leave-message} 自定义，支持 {bot} 和 {user} 占位符。
 *
 * <p>需要在 QQ 开放平台回调配置中勾选「群成员减少」事件订阅。
 */
public class LeaveHandler implements BotMessageHandler {

    private static final String DEFAULT_LEAVE = "{user} 离开了我们";

    private final String leaveMessage;
    private final Set<String> allowedGroups;

    public LeaveHandler(BotConfig config, Set<String> allowedGroups) {
        this.leaveMessage = config.getStringResolved("leave-message", DEFAULT_LEAVE);
        this.allowedGroups = allowedGroups;
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        if (!"GROUP_MEMBER_REMOVE".equals(event.getEventType())) return false;
        return isGroupAllowed(event.getConversationId());
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String text = leaveMessage;
        if (text == null || text.isEmpty()) return;
        String username = event.getUsername() != null ? event.getUsername() : "群成员";
        text = text.replace("{bot}", org.windy.xingtubot.common.runtime.BotRuntimeState.getBotName())
                   .replace("{user}", username);
        event.replyMarkdown(text, null);
    }

    private boolean isGroupAllowed(String groupOpenid) {
        if (allowedGroups == null || allowedGroups.isEmpty() || allowedGroups.contains("*")) return true;
        return groupOpenid != null && allowedGroups.contains(groupOpenid);
    }

    @Override
    public String name() {
        return "leave";
    }

    @Override
    public int priority() {
        return 5;
    }
}
