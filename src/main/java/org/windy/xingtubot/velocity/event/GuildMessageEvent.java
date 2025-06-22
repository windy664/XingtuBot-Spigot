package org.windy.xingtubot.velocity.event;

import com.velocitypowered.api.event.ResultedEvent;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Consumer;


public class GuildMessageEvent implements ResultedEvent<GuildMessageEvent.GenericResult> {

    private final String guildId;
    private final String formId;
    private final String message;
    private final Consumer<String> replyCallback;
    private GenericResult result = GenericResult.allowed();
    private boolean handled = false;

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

    public void reply(String message) {
        if (!handled) {
            replyCallback.accept(message);
            handled = true;
        }
    }

    @Override
    public GenericResult getResult() {
        return result;
    }

    @Override
    public void setResult(GenericResult result) {
        this.result = result;
    }

    public static final class GenericResult implements Result {

        private static final GenericResult ALLOWED = new GenericResult(true);
        private static final GenericResult DENIED = new GenericResult(false);

        private final boolean allowed;

        private GenericResult(boolean allowed) {
            this.allowed = allowed;
        }

        @Override
        public boolean isAllowed() {
            return allowed;
        }

        public static GenericResult allowed() {
            return ALLOWED;
        }

        public static GenericResult denied() {
            return DENIED;
        }
    }
}