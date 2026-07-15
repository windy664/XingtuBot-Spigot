package org.windy.xingtubot.common.event;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Platform-neutral inbound bot message event.
 *
 * <p>The legacy {@code guildId} name is intentionally kept because older
 * modules and QQ channel events use guild terminology. In QQ group/C2C events
 * this value is the conversation id we route replies through:
 * {@code group_openid} for group messages and {@code user_openid} for direct
 * messages.</p>
 */
public class BotMessageEvent implements BotMessageContext {
    private final String guildId;
    private final String formId;
    private final String message;
    private final BotReplier replier;
    private final String username;
    private final String eventType;
    private List<String> imageUrls = Collections.emptyList();

    /** Compatibility constructor for simple text replies. */
    public BotMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
        this(guildId, formId, message, replyCallback == null ? null : (BotReplier) replyCallback::accept, null, null);
    }

    /** Constructor with the richer reply capability used by OpenAPI transports. */
    public BotMessageEvent(String guildId, String formId, String message, BotReplier replier) {
        this(guildId, formId, message, replier, null, null);
    }

    /** Constructor carrying the QQ display name when available. */
    public BotMessageEvent(String guildId, String formId, String message, BotReplier replier, String username) {
        this(guildId, formId, message, replier, username, null);
    }

    /** Full constructor carrying the raw QQ event type for message classification. */
    public BotMessageEvent(String guildId, String formId, String message, BotReplier replier,
                           String username, String eventType) {
        this.guildId = guildId;
        this.formId = formId;
        this.message = message;
        this.replier = replier;
        this.username = username;
        this.eventType = eventType;
    }

    /** Inbound image URLs attached to the message. Never returns null. */
    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls == null ? Collections.emptyList() : imageUrls;
    }

    /**
     * Legacy context id accessor.
     *
     * <p>For QQ channel events this maps naturally to the official guild
     * terminology. For QQ group/C2C events this field is the conversation id
     * kept under the old name for compatibility.</p>
     */
    /**
     * @deprecated Use {@link #getConversationId()}.
     */
    @Deprecated
    public String getGuildId() {
        return guildId;
    }

    /**
     * Transport-neutral conversation id. Prefer this in new common code when
     * the exact QQ protocol field is not important.
     */
    public String getConversationId() {
        return guildId;
    }

    /** Legacy sender id accessor. The name is kept for binary/source compatibility. */
    /**
     * @deprecated Use {@link #getSenderId()}.
     */
    @Deprecated
    public String getFormId() {
        return formId;
    }

    /** Transport-neutral sender id. Prefer this in new common code. */
    public String getSenderId() {
        return formId;
    }

    /** QQ display name from author.username when available. */
    public String getUsername() {
        return username;
    }

    /** Raw QQ event type, for example GROUP_AT_MESSAGE_CREATE. May be null. */
    public String getEventType() {
        return eventType;
    }

    /** Stable message type for extensions. Prefer this over comparing raw QQ event strings. */
    public BotMessageType getMessageType() {
        return BotMessageType.fromRawEventType(eventType);
    }

    /** True for QQ group messages that mention the bot. */
    public boolean isGroupAtMessage() {
        return getMessageType() == BotMessageType.GROUP_AT;
    }

    /** True for QQ group messages, including bot mentions. */
    public boolean isGroupMessage() {
        BotMessageType type = getMessageType();
        return type == BotMessageType.GROUP_AT || type == BotMessageType.GROUP;
    }

    public String getMessage() {
        return message;
    }

    /** Legacy reply capability accessor. */
    /**
     * @deprecated Use {@link #getReply()}.
     */
    @Deprecated
    public BotReplier getReplier() {
        return replier;
    }

    /** Reply capability. Prefer this name in new code. */
    public MessageReply getReply() {
        return replier;
    }

    public void reply(String replyMessage) {
        if (replier != null) {
            replier.replyText(replyMessage);
        }
    }

    public void replyImage(String imageUrl, String content) {
        if (replier != null) {
            replier.replyImage(imageUrl, content);
        }
    }

    public void replyImageData(byte[] imageBytes, String content) {
        if (replier != null) {
            replier.replyImageData(imageBytes, content);
        }
    }

    public void replyVoice(String voiceUrl) {
        if (replier != null) {
            replier.replyVoice(voiceUrl);
        }
    }

    public void replyVoiceData(byte[] audioBytes) {
        if (replier != null) {
            replier.replyVoiceData(audioBytes);
        }
    }

    public void replyVideo(String videoUrl, String content) {
        if (replier != null) {
            replier.replyVideo(videoUrl, content);
        }
    }

    public void replyEmbed(String embedJson) {
        if (replier != null) {
            replier.replyEmbed(embedJson);
        }
    }

    public void replyMarkdown(String content, String keyboardTemplateId) {
        if (replier != null) {
            replier.replyMarkdown(content, keyboardTemplateId);
        }
    }

    public void replyKeyboard(String markdownContent, String keyboardJson) {
        if (replier != null) {
            replier.replyKeyboard(markdownContent, keyboardJson);
        }
    }

    public void replyArk(String arkJson) {
        if (replier != null) {
            replier.replyArk(arkJson);
        }
    }
}
