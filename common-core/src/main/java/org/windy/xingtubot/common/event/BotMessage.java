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
}
