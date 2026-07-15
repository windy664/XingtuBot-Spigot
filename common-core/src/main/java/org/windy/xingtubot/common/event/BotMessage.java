package org.windy.xingtubot.common.event;

import java.util.List;

/**
 * Read-only inbound bot message.
 */
public interface BotMessage {

    String getConversationId();

    String getSenderId();

    String getMessage();

    String getUsername();

    List<String> getImageUrls();

    BotMessageType getMessageType();

    /**
     * Raw transport event type when available.
     */
    default String getEventType() {
        return null;
    }

    default boolean isGroupAtMessage() {
        return getMessageType() == BotMessageType.GROUP_AT;
    }

    default boolean isGroupMessage() {
        BotMessageType type = getMessageType();
        return type == BotMessageType.GROUP_AT || type == BotMessageType.GROUP;
    }
}
