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

    default void replyImageData(byte[] imageBytes, String content) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyImageData(imageBytes, content);
        }
    }

    default void replyVoice(String voiceUrl) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyVoice(voiceUrl);
        }
    }

    default void replyVoiceData(byte[] audioBytes) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyVoiceData(audioBytes);
        }
    }

    default void replyVideo(String videoUrl, String content) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyVideo(videoUrl, content);
        }
    }

    default void replyEmbed(String embedJson) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyEmbed(embedJson);
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

    default void replyKeyboard(String markdownContent, String keyboardJson) {
        MessageReply reply = getReply();
        if (reply != null) {
            reply.replyKeyboard(markdownContent, keyboardJson);
        }
    }
}
