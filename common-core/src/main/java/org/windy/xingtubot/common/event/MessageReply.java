package org.windy.xingtubot.common.event;

/**
 * Reply capability for an inbound bot message.
 */
public interface MessageReply {

    void replyText(String text);

    default void replyImage(String imageUrl, String content) {
        replyText(content != null ? content : "");
    }

    default void replyImageData(byte[] imageBytes, String content) {
        replyText(content != null ? content : "");
    }

    default void replyVoice(String voiceUrl) {
    }

    default void replyVoiceData(byte[] audioBytes) {
    }

    default void replyVideo(String videoUrl, String content) {
        replyText(content != null ? content : "");
    }

    default void replyEmbed(String embedJson) {
    }

    default void replyMarkdown(String content, String keyboardTemplateId) {
        replyText(content);
    }

    default void replyKeyboard(String markdownContent, String keyboardJson) {
        replyText(markdownContent);
    }

    default void replyArk(String arkJson) {
    }
}
