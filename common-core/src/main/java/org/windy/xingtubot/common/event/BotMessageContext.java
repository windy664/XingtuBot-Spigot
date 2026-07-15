package org.windy.xingtubot.common.event;

/**
 * Inbound bot message plus reply capability.
 */
public interface BotMessageContext extends BotMessage {

    MessageReply getReply();

    default void reply(String content) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyText(content);
        }
    }

    default void replyImage(String imageUrl, String content) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyImage(imageUrl, content);
        }
    }

    default void replyMarkdown(String content, String keyboardTemplateId) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyMarkdown(content, keyboardTemplateId);
        }
    }

    default void replyArk(String arkJson) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyArk(arkJson);
        }
    }
}
