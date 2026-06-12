package org.windy.xingtubot.bukkit.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.function.Consumer;

/**
 * Spigot 内部事件总线使用的群消息事件，由 SpigotBotBridge 从 common 的
 * BotMessageEvent 转换而来，供各模块（whitelist / chatreply / aichat）订阅。
 */
public class GuildMessageEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final String guildId;
    private final String formId;
    private final String message;
    private final Consumer<String> replyCallback;

    public GuildMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
        this.guildId = guildId;
        this.formId = formId;
        this.message = message;
        this.replyCallback = replyCallback;
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
        if (replyCallback != null) {
            replyCallback.accept(replyMessage);
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
