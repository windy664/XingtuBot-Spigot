package org.windy.xingtubot.common.api;

/**
 * Public capability for outbound bot messages.
 */
public interface MessageSender {

    void sendToGroupMarkdown(String groupOpenId, String markdownContent, String keyboardTemplateId);
}
