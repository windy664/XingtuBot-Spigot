package org.windy.xingtubot.core.api;

import java.util.function.Consumer;

public class GuildMessageEvent {
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

    public String getGuildId() { return guildId; }
    public String getFormId() { return formId; }
    public String getMessage() { return message; }
    public void reply(String msg) { if (replyCallback != null) replyCallback.accept(msg); }
}