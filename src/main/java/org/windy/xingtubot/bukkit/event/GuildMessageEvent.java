package org.windy.xingtubot.bukkit.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.function.Consumer;

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

    // 外部插件调用此方法设置回复
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