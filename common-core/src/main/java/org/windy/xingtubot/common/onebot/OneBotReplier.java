package org.windy.xingtubot.common.onebot;

import org.windy.xingtubot.common.event.BotReplier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OneBot 11 协议回复器。
 *
 * <p><b>被动回复窗口</b>：收到消息后 5 分钟内，带 {@code reply} 消息段引用原消息（回复感）。
 * 窗口过期或 {@code messageId=0}（非消息事件）时退化为主动发送。
 */
public final class OneBotReplier implements BotReplier {

    private final OneBotApiClient apiClient;
    private final String targetId;    // groupId 或 userId
    private final int messageId;      // 0=不可被动回复
    private final long windowExpireAt;

    private final boolean isGroup;

    /**
     * @param apiClient     OneBot API 客户端
     * @param targetId      目标群 ID 或用户 ID
     * @param messageId     被动回复依赖的消息 id（0 表示不引用）
     * @param replyWindowMs 被动回复窗口时长（毫秒），传 0 表示无窗口
     */
    public OneBotReplier(OneBotApiClient apiClient, String targetId, int messageId, long replyWindowMs) {
        this.apiClient = apiClient;
        this.targetId = targetId;
        this.messageId = messageId;
        this.windowExpireAt = replyWindowMs > 0 ? System.currentTimeMillis() + replyWindowMs : 0;
        this.isGroup = true; // 默认按群聊处理，调用方按需调整
    }

    /**
     * 判断是否在被动回复窗口内且可引用。
     */
    private boolean canReplyPassive() {
        return messageId != 0 && System.currentTimeMillis() < windowExpireAt;
    }

    @Override
    public void replyText(String text) {
        if (text == null || text.isEmpty()) return;
        sendWithFallback(Collections.singletonList(MsgSegment.text(text)));
    }

    @Override
    public void replyMarkdown(String content, String keyboardTemplateId) {
        if (content == null || content.isEmpty()) return;
        sendWithFallback(Collections.singletonList(MsgSegment.markdown(content)));
    }

    @Override
    public void replyImage(String imageUrl, String content) {
        if (imageUrl == null) {
            replyText(content);
            return;
        }
        List<MsgSegment> segments = new ArrayList<>();
        segments.add(MsgSegment.image(imageUrl));
        if (content != null && !content.trim().isEmpty()) {
            segments.add(MsgSegment.text(content));
        }
        sendWithFallback(segments);
    }

    @Override
    public void replyImageData(byte[] imageBytes, String content) {
        // OB11 不支持 base64 直传，降级文本
        replyText(content != null ? content : "[图片]");
    }

    @Override
    public void replyVoice(String voiceUrl) {
        if (voiceUrl == null) return;
        sendWithFallback(Collections.singletonList(MsgSegment.record(voiceUrl)));
    }

    @Override
    public void replyVoiceData(byte[] audioBytes) {
        // OB11 不支持语音字节直传，忽略
    }

    @Override
    public void replyVideo(String videoUrl, String content) {
        replyText(content != null ? content : "[视频]");
    }

    // ==================== 内部 ====================

    private void sendWithFallback(List<MsgSegment> segments) {
        if (apiClient == null || targetId == null) return;

        List<MsgSegment> finalSegments = segments;
        if (canReplyPassive()) {
            finalSegments = new ArrayList<>();
            finalSegments.add(MsgSegment.reply(String.valueOf(messageId)));
            finalSegments.addAll(segments);
        }

        try {
            // 优先尝试群消息
            apiClient.sendGroupMessage(targetId, finalSegments);
        } catch (Exception e) {
            // 失败则尝试私聊
            try {
                apiClient.sendPrivateMessage(targetId, finalSegments);
            } catch (Exception ignored) {
            }
        }
    }
}
