package org.windy.xingtubot.common.onebot;

import com.google.gson.JsonObject;

/**
 * OneBot 11 出站消息段。
 *
 * <p>替代 cnlimiter 的 {@code MsgUtils.builder()}，与现有 {@code Md}/{@code Keyboards} 工厂风格一致。
 *
 * <p>发出的 message 字段**一律用消息段数组**（文档 5.1 决策），即使纯文本也用数组包裹。
 */
public final class MsgSegment {

    private final String type;
    private final JsonObject data;

    public MsgSegment(String type, JsonObject data) {
        this.type = type;
        this.data = data;
    }

    public String type() {
        return type;
    }

    public JsonObject data() {
        return data;
    }

    // ==================== 工厂方法 ====================

    public static MsgSegment text(String t) {
        return new MsgSegment("text", prop("text", t != null ? t : ""));
    }

    public static MsgSegment at(String qq) {
        return new MsgSegment("at", prop("qq", qq != null ? qq : ""));
    }

    public static MsgSegment image(String url) {
        return new MsgSegment("image", prop("url", url != null ? url : ""));
    }

    public static MsgSegment reply(String messageId) {
        return new MsgSegment("reply", prop("id", messageId != null ? messageId : ""));
    }

    public static MsgSegment markdown(String content) {
        return new MsgSegment("markdown", prop("content", content != null ? content : ""));
    }

    /** 语音消息段。 */
    public static MsgSegment record(String url) {
        return new MsgSegment("record", prop("url", url != null ? url : ""));
    }

    private static JsonObject prop(String k, String v) {
        JsonObject obj = new JsonObject();
        obj.addProperty(k, v);
        return obj;
    }
}
