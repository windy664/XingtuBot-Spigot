package org.windy.xingtubot.bukkit.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.windy.xingtubot.common.event.BotReplier;

import java.util.function.Consumer;

/**
 * Spigot 内部事件总线使用的群消息事件，由主类从 common 的
 * BotMessageEvent 转换而来，供各模块（whitelist / chatreply / aichat）订阅。
 *
 * <p>富回复（图片/Markdown/Ark）由 {@link BotReplier} 提供：Webhook 通道全支持，
 * WSS 通道自动降级为文本。
 */
public class GuildMessageEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final String guildId;
    private final String formId;
    private final String message;
    private final BotReplier replier;
    private final String username; // QQ 昵称
    private final String eventType; // QQ 事件类型（GROUP_AT_MESSAGE_CREATE 等），用于群/私聊判定
    private java.util.List<String> imageUrls = java.util.Collections.emptyList(); // 入站图片 URL（转发进游戏用），永不 null

    /** 兼容旧用法：仅文本回复（WSS 通道）。 */
    public GuildMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
        this(guildId, formId, message, replyCallback == null ? null : (BotReplier) replyCallback::accept, null);
    }

    /** 富回复：传入支持图片/Markdown/Ark 的回复器。 */
    public GuildMessageEvent(String guildId, String formId, String message, BotReplier replier) {
        this(guildId, formId, message, replier, null);
    }

    /** 带 QQ 昵称的构造。 */
    public GuildMessageEvent(String guildId, String formId, String message, BotReplier replier, String username) {
        this(guildId, formId, message, replier, username, null);
    }

    /** 带 QQ 昵称 + 事件类型的完整构造。 */
    public GuildMessageEvent(String guildId, String formId, String message, BotReplier replier,
                             String username, String eventType) {
        this.guildId = guildId;
        this.formId = formId;
        this.message = message;
        this.replier = replier;
        this.username = username;
        this.eventType = eventType;
    }

    /** 入站图片 URL 列表（群消息附带的图片），永不为 null。 */
    public java.util.List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(java.util.List<String> imageUrls) {
        this.imageUrls = (imageUrls == null) ? java.util.Collections.emptyList() : imageUrls;
    }

    /** QQ 昵称（webhook 事件的 author.username），可能为 null。 */
    public String getUsername() {
        return username;
    }

    /** QQ 事件类型（GROUP_AT_MESSAGE_CREATE 等），可能为 null。 */
    public String getEventType() {
        return eventType;
    }

    public String getGuildId() {
        return guildId;
    }

    public String getFormId() {
        return formId;
    }

    public String getMessage() {
        return message;
    }

    /** 获取底层回复器（供 SpigotCommandHandler 构造 BotMessageEvent 用）。 */
    public BotReplier getReplier() {
        return replier;
    }

    public void reply(String replyMessage) {
        if (replier != null) {
            replier.replyText(replyMessage);
        }
    }

    /** 回复图片（WSS 通道降级为文本）。 */
    public void replyImage(String imageUrl, String content) {
        if (replier != null) {
            replier.replyImage(imageUrl, content);
        }
    }

    /** 回复 Markdown（可带键盘模板；WSS 通道降级为文本）。 */
    public void replyMarkdown(String content, String keyboardTemplateId) {
        if (replier != null) {
            replier.replyMarkdown(content, keyboardTemplateId);
        }
    }

    /** 回复 Ark 卡片（入参 ark 的 JSON 字符串；WSS 通道忽略）。 */
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
