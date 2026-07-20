package org.windy.xingtubot.common.poll;

import com.google.gson.JsonObject;
import org.windy.xingtubot.common.qq.QqOpenApiClient;
import org.windy.xingtubot.common.event.MessageReply;
import org.windy.xingtubot.common.platform.PlatformAdapter;
import org.windy.xingtubot.common.util.Md;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAPI 模式的 MessageReply 实现。
 * 独立顶级类，避免 Youer 混合端 classloader 对内部类的加载问题。
 */
public class OpenApiBotReplier implements MessageReply {

    /** 一次 OpenAPI 调用，入参为自增的 msg_seq；允许抛出受检异常。 */
    public interface ApiCall {
        void run(int seq) throws Exception;
    }

    private final QqOpenApiClient api;
    private final PlatformAdapter adapter;
    private final AtomicInteger seq;
    private final String fGroup;
    private final String fUser;
    private final String fMsgId;
    private final String fEventId;
    private final boolean isGroup;

    public OpenApiBotReplier(QqOpenApiClient api, PlatformAdapter adapter,
                             AtomicInteger seq,
                             String fGroup, String fUser, String fMsgId, String fEventId,
                             boolean isGroup) {
        this.api = api;
        this.adapter = adapter;
        this.seq = seq;
        this.fGroup = fGroup;
        this.fUser = fUser;
        this.fMsgId = fMsgId;
        this.fEventId = fEventId;
        this.isGroup = isGroup;
    }

    @Override
    public void replyText(String text) {
        // markdown-only：纯文本统一包成 markdown 走 markdown 通道（产品定位要求 bot 具备 markdown 权限）。
        String md = Md.plain(text);
        send(s -> {
            if (fMsgId != null) {
                if (isGroup) api.sendGroupMarkdown(fGroup, md, null, fMsgId, s);
                else api.sendC2CMarkdown(fUser, md, null, fMsgId, s);
            } else {
                if (isGroup) api.sendGroupMarkdownByEvent(fGroup, md, fEventId, s);
                else api.sendC2CMarkdownByEvent(fUser, md, fEventId, s);
            }
        });
    }

    /** 直发音频字节（供 TTS 合成结果与 {@link #replyVoiceData} 共用）。 */
    private void sendVoiceBytes(byte[] audioBytes) {
        String id = resolveId();
        int s = seq.getAndIncrement();
        try {
            if (isGroup) api.sendGroupVoiceData(fGroup, audioBytes, id, s);
            else api.sendC2CVoiceData(fUser, audioBytes, id, s);
        } catch (Exception e) {
            adapter.log("[Voice] 语音字节发送失败: " + e.getMessage());
        }
    }

    @Override
    public void replyVoiceData(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) return;
        adapter.runAsync(() -> sendVoiceBytes(audioBytes));
    }

    @Override
    public void replyImage(String imageUrl, String content) {
        String id = resolveId();
        send(s -> {
            if (isGroup) api.sendGroupImage(fGroup, imageUrl, content, id, s);
            else api.sendC2CImage(fUser, imageUrl, content, id, s);
        });
    }

    @Override
    public void replyImageData(byte[] imageBytes, String content) {
        String id = resolveId();
        send(s -> {
            if (isGroup) {
                api.sendGroupImageData(fGroup, imageBytes, content, id, s);
            } else {
                String b64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
                String fi = api.uploadC2CFileData(fUser, QqOpenApiClient.FILE_IMAGE, b64);
                api.sendC2CMedia(fUser, fi, content, id, s);
            }
        });
    }

    @Override
    public void replyVoice(String voiceUrl) {
        String id = resolveId();
        send(s -> {
            if (isGroup) api.sendGroupVoice(fGroup, voiceUrl, id, s);
            else api.sendC2CVoice(fUser, voiceUrl, id, s);
        });
    }

    @Override
    public void replyVideo(String videoUrl, String content) {
        String id = resolveId();
        send(s -> {
            if (isGroup) api.sendGroupVideo(fGroup, videoUrl, content, id, s);
            else api.sendC2CVideo(fUser, videoUrl, content, id, s);
        });
    }

    @Override
    public void replyMarkdown(String content, String keyboardTemplateId) {
        send(s -> {
            if (fMsgId != null) {
                if (isGroup) api.sendGroupMarkdown(fGroup, content, keyboardTemplateId, fMsgId, s);
                else api.sendC2CMarkdown(fUser, content, keyboardTemplateId, fMsgId, s);
            } else {
                if (isGroup) api.sendGroupMarkdownByEvent(fGroup, content, fEventId, s);
                else api.sendC2CMarkdownByEvent(fUser, content, fEventId, s);
            }
        });
    }

    @Override
    public void replyKeyboard(String markdownContent, String keyboardJson) {
        String id = resolveId();
        send(s -> {
            JsonObject md = new JsonObject();
            md.addProperty("content", markdownContent);
            JsonObject keyboard = parseObj(keyboardJson);
            if (isGroup) api.sendGroupMarkdownRaw(fGroup, md, keyboard, id, s);
            else api.sendC2CMarkdown(fUser, markdownContent, null, id, s);
        });
    }

    @Override
    public void replyEmbed(String embedJson) {
        JsonObject embed = parseObj(embedJson);
        if (embed == null) return;
        String id = resolveId();
        send(s -> {
            if (isGroup) api.sendGroupEmbed(fGroup, embed, id, s);
            else api.sendC2CEmbed(fUser, embed, id, s);
        });
    }

    @Override
    public void replyArk(String arkJson) {
        JsonObject ark = parseObj(arkJson);
        if (ark == null) return;
        String id = resolveId();
        send(s -> {
            if (isGroup) api.sendGroupArk(fGroup, ark, id, s);
            else api.sendC2CArk(fUser, ark, id, s);
        });
    }

    /** JSON 字符串 → JsonObject（本类内部用，gson 不跨插件边界）。空/非法返回 null。 */
    private static JsonObject parseObj(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveId() {
        return fMsgId != null ? fMsgId : fEventId;
    }

    private void send(ApiCall call) {
        if (!isGroup && fUser == null) {
            adapter.log("[QQ] 无法判断回复目标，已跳过");
            return;
        }
        adapter.runAsync(() -> {
            try {
                call.run(seq.getAndIncrement());
            } catch (Exception e) {
                adapter.log("[QQ] OpenAPI 回复失败: " + e.getMessage());
            }
        });
    }
}
