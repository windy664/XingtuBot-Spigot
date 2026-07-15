package org.windy.xingtubot.bukkit.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.event.BotMessageType;
import org.windy.xingtubot.common.event.MessageReply;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Bukkit event bridged from {@code BotMessageEvent}.
 *
 * <p>Use {@link BotMessageContext} accessors when modules need a conversation
 * id or sender id.</p>
 */
public class GuildMessageEvent extends Event implements BotMessageContext {
    private static final HandlerList handlers = new HandlerList();

    private final String guildId;
    private final String formId;
    private final String message;
    private final MessageReply replier;
    private final String username;
    private final String eventType;
    private List<String> imageUrls = Collections.emptyList();

    /** Compatibility constructor for simple text replies. */
    public GuildMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
        this(guildId, formId, message, replyCallback == null ? null : (MessageReply) replyCallback::accept, null);
    }

    /** Constructor with the richer reply capability used by OpenAPI transports. */
    public GuildMessageEvent(String guildId, String formId, String message, MessageReply replier) {
        this(guildId, formId, message, replier, null);
    }

    /** Constructor carrying the QQ display name when available. */
    public GuildMessageEvent(String guildId, String formId, String message, MessageReply replier, String username) {
        this(guildId, formId, message, replier, username, null);
    }

    /** Full constructor carrying the raw QQ event type for message classification. */
    public GuildMessageEvent(String guildId, String formId, String message, MessageReply replier,
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

    /** QQ display name from author.username when available. */
    public String getUsername() {
        return username;
    }

    /** Raw QQ event type, for example GROUP_AT_MESSAGE_CREATE. May be null. */
    public String getEventType() {
        return eventType;
    }

    /** Transport-neutral conversation id. Prefer this in new code. */
    public String getConversationId() {
        return guildId;
    }

    /** Transport-neutral sender id. Prefer this in new code. */
    public String getSenderId() {
        return formId;
    }

    public String getMessage() {
        return message;
    }

    /** Stable message type for modules. */
    public BotMessageType getMessageType() {
        return BotMessageType.fromRawEventType(eventType);
    }

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

    public void replyMarkdown(String content, String keyboardTemplateId) {
        if (replier != null) {
            replier.replyMarkdown(content, keyboardTemplateId);
        }
    }

    public void replyArk(String arkJson) {
        if (replier != null) {
            replier.replyArk(arkJson);
        }
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
