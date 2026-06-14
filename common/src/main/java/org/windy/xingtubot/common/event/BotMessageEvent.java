package org.windy.xingtubot.common.event;

import com.google.gson.JsonObject;

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

    /** 兼容旧用法：仅文本回复（WSS 通道用这个）。 */
    public BotMessageEvent(String guildId, String formId, String message, Consumer<String> replyCallback) {
        this(guildId, formId, message, replyCallback == null ? null : (BotReplier) replyCallback::accept);
    }

    /** 富回复：Webhook 通道传入支持图片/Markdown/Ark 的回复器。 */
    public BotMessageEvent(String guildId, String formId, String message, BotReplier replier) {
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

    /** 回复语音（silk url；不支持的通道忽略）。 */
    public void replyVoice(String voiceUrl) {
        if (replier != null) {
            replier.replyVoice(voiceUrl);
        }
    }

    /** 回复视频（mp4 url；不支持的通道降级为文本）。 */
    public void replyVideo(String videoUrl, String content) {
        if (replier != null) {
            replier.replyVideo(videoUrl, content);
        }
    }

    /** 回复 Embed（实验性；不支持的通道忽略）。 */
    public void replyEmbed(JsonObject embed) {
        if (replier != null) {
            replier.replyEmbed(embed);
        }
    }

    /** 回复 Markdown（可带键盘模板；不支持的通道降级为文本）。 */
    public void replyMarkdown(String content, String keyboardTemplateId) {
        if (replier != null) {
            replier.replyMarkdown(content, keyboardTemplateId);
        }
    }

    /** 回复 Ark 卡片（不支持的通道忽略）。 */
    public void replyArk(JsonObject ark) {
        if (replier != null) {
            replier.replyArk(ark);
        }
    }
}
