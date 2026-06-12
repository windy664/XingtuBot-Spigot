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
    private final Consumer<String> replyCallback;

    public BotMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
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

    /** 回复消息，回写至机器人框架 */
    public void reply(String replyMessage) {
        if (replyCallback != null) {
            replyCallback.accept(replyMessage);
        }
    }
}
