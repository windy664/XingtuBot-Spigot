package org.windy.xingtubot.bukkit.event;

import com.google.gson.JsonObject;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.windy.xingtubot.common.event.BotReplier;

import java.util.function.Consumer;

/**
 * Spigot 内部事件总线使用的群消息事件，由 SpigotBotBridge / 主类从 common 的
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

    /** 兼容旧用法：仅文本回复（WSS 通道）。 */
    public GuildMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
        this(guildId, formId, message, replyCallback == null ? null : (BotReplier) replyCallback::accept);
    }

    /** 富回复：传入支持图片/Markdown/Ark 的回复器。 */
    public GuildMessageEvent(String guildId, String formId, String message, BotReplier replier) {
        this.guildId = guildId;
        this.formId = formId;
        this.message = message;
        this.replier = replier;
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

    /** 回复 Ark 卡片（WSS 通道忽略）。 */
    public void replyArk(JsonObject ark) {
        if (replier != null) {
            replier.replyArk(ark);
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
