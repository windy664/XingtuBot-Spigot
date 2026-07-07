package org.windy.xingtubot.common.event;

import java.util.function.Consumer;

/**
 * 平台无关的机器人消息事件模型。
 * 取代原先分散在各处的三个重复事件类。
 */
public class BotMessageEvent {
    private final String guildId;
    private final String formId;
    private final String message;
    private final BotReplier replier;
    private final String username;  // QQ 昵称（webhook 事件带的 author.username）
    private final String eventType; // QQ 事件类型（如 GROUP_AT_MESSAGE_CREATE / GROUP_MESSAGE_CREATE）
    // 入站富媒体图片 URL（attachments 里 content_type=image 的）；永不为 null。
    private java.util.List<String> imageUrls = java.util.Collections.emptyList();

    /** 兼容旧用法：仅文本回复（WSS 通道用这个）。 */
    public BotMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
        this(guildId, formId, message, replyCallback == null ? null : (BotReplier) replyCallback::accept, null, null);
    }

    /** 富回复：Webhook 通道传入支持图片/Markdown/Ark 的回复器。 */
    public BotMessageEvent(String guildId, String formId, String message, BotReplier replier) {
        this(guildId, formId, message, replier, null, null);
    }

    /** 带 QQ 昵称的完整构造。 */
    public BotMessageEvent(String guildId, String formId, String message, BotReplier replier, String username) {
        this(guildId, formId, message, replier, username, null);
    }

    /** 带事件类型的完整构造（用于 listen-mode 判断）。 */
    public BotMessageEvent(String guildId, String formId, String message, BotReplier replier,
                           String username, String eventType) {
        this.guildId = guildId;
        this.formId = formId;
        this.message = message;
        this.replier = replier;
        this.username = username;
        this.eventType = eventType;
    }

    /** 入站图片 URL 列表（群消息里附带的图片）；永不为 null，无图片则为空。 */
    public java.util.List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(java.util.List<String> imageUrls) {
        this.imageUrls = (imageUrls == null) ? java.util.Collections.emptyList() : imageUrls;
    }

    public String getGuildId() {
        return guildId;
    }

    public String getFormId() {
        return formId;
    }

    /** QQ 昵称（webhook 事件的 author.username），可能为 null。 */
    public String getUsername() {
        return username;
    }

    /** QQ 事件类型（如 GROUP_AT_MESSAGE_CREATE / GROUP_MESSAGE_CREATE），可能为 null。 */
    public String getEventType() {
        return eventType;
    }

    /** 是否为群 @机器人 消息（GROUP_AT_MESSAGE_CREATE）。 */
    public boolean isGroupAtMessage() {
        return eventType != null && eventType.equals("GROUP_AT_MESSAGE_CREATE");
    }

    /** 是否为群消息（GROUP_AT_MESSAGE_CREATE 或 GROUP_MESSAGE_CREATE）。 */
    public boolean isGroupMessage() {
        return eventType != null && (eventType.equals("GROUP_AT_MESSAGE_CREATE")
                || eventType.equals("GROUP_MESSAGE_CREATE"));
    }

    public String getMessage() {
        return message;
    }

    /** 回复器，便于在转换成平台事件时透传富回复能力。 */
    public BotReplier getReplier() {
        return replier;
    }

    /** 回复文本。 */
    public void reply(String replyMessage) {
        if (replier != null) {
            replier.replyText(replyMessage);
        }
    }

    /** 回复图片（不支持的通道降级为文本）。 */
    public void replyImage(String imageUrl, String content) {
        if (replier != null) {
            replier.replyImage(imageUrl, content);
        }
    }

    /** 回复图片（base64 字节直传，不依赖公网/SCF）。 */
    public void replyImageData(byte[] imageBytes, String content) {
        if (replier != null) {
            replier.replyImageData(imageBytes, content);
        }
    }

    /** 回复语音（silk url；不支持的通道忽略）。 */
    public void replyVoice(String voiceUrl) {
        if (replier != null) {
            replier.replyVoice(voiceUrl);
        }
    }

    /** 回复语音（音频字节直传 silk/mp3；不支持的通道忽略）。 */
    public void replyVoiceData(byte[] audioBytes) {
        if (replier != null) {
            replier.replyVoiceData(audioBytes);
        }
    }

    /** 回复视频（mp4 url；不支持的通道降级为文本）。 */
    public void replyVideo(String videoUrl, String content) {
        if (replier != null) {
            replier.replyVideo(videoUrl, content);
        }
    }

    /** 回复 Embed（实验性；入参 embed 的 JSON 字符串；不支持的通道忽略）。 */
    public void replyEmbed(String embedJson) {
        if (replier != null) {
            replier.replyEmbed(embedJson);
        }
    }

    /** 回复 Markdown（可带键盘模板；不支持的通道降级为文本）。 */
    public void replyMarkdown(String content, String keyboardTemplateId) {
        if (replier != null) {
            replier.replyMarkdown(content, keyboardTemplateId);
        }
    }

    /** 回复 Markdown + 内联按钮键盘（keyboardJson 为键盘 JSON 字符串，避免 gson 类型跨插件失配）。 */
    public void replyKeyboard(String markdownContent, String keyboardJson) {
        if (replier != null) {
            replier.replyKeyboard(markdownContent, keyboardJson);
        }
    }

    /** 回复 Ark 卡片（入参 ark 的 JSON 字符串；不支持的通道忽略）。 */
    public void replyArk(String arkJson) {
        if (replier != null) {
            replier.replyArk(arkJson);
        }
    }
}
