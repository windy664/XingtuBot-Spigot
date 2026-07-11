package org.windy.xingtubot.common.event;

import java.util.function.Consumer;

/**
 * 平台无关的机器人消息事件模型。
 *
 * <p>取代原先分散在各处的三个重复事件类。已针对多协议（QQ 官方 / OneBot 11）统一字段语义。
 *
 * <p><b>消息类型枚举</b>：{@link MessageType#GROUP} = 群聊，{@link MessageType#PRIVATE} = 私聊。
 */
public class BotMessageEvent {

    /** 消息类型：群聊 / 私聊。 */
    public enum MessageType {
        GROUP,
        PRIVATE
    }

    /** 群聊 ID（平台无关字符串；官方=group_openid，OB11=群号字符串）。私聊时为 null。 */
    private final String groupId;

    /** 协议层发送者 ID（官方=member_openid/user_openid，OB11=user_id 字符串）。 */
    private final String formId;

    /** 适配器映射后的平台用户 uid（业务层做身份判定用）。 */
    private final String senderUid;

    /** 消息文本内容。 */
    private final String message;

    /** 回复器。 */
    private final BotReplier replier;

    /** 发送者昵称。 */
    private final String username;

    /** 消息类型（群聊/私聊）。 */
    private final MessageType messageType;

    /** 原始事件类型字符串（协议相关；业务层优先读 messageType 而非此字段）。 */
    private final String eventType;

    /** 入站富媒体图片 URL（永不为 null，无图片则为空列表）。 */
    private java.util.List<String> imageUrls = java.util.Collections.emptyList();

    // ==================== 构造器 ====================

    /**
     * 全参数构造器。
     *
     * @param groupId     群聊 ID（私聊传 null）
     * @param formId      协议层发送者 ID
     * @param senderUid   适配器映射后的平台用户 uid
     * @param message     消息文本
     * @param replier     回复器
     * @param username    发送者昵称
     * @param messageType 消息类型（GROUP / PRIVATE）
     * @param eventType   原始事件类型字符串
     */
    public BotMessageEvent(String groupId, String formId, String senderUid, String message,
                           BotReplier replier, String username,
                           MessageType messageType, String eventType) {
        this.groupId = groupId;
        this.formId = formId;
        this.senderUid = senderUid;
        this.message = message;
        this.replier = replier;
        this.username = username;
        this.messageType = messageType;
        this.eventType = eventType;
    }

    /**
     * 简化构造器：适用于无需 senderUid 映射的场景（内部构造，会自动用 formId 作为 senderUid）。
     *
     * @param groupId     群聊 ID（私聊传 null）
     * @param formId      协议层发送者 ID
     * @param message     消息文本
     * @param replier     回复器
     * @param username    发送者昵称
     * @param messageType 消息类型
     * @param eventType   原始事件类型
     */
    public BotMessageEvent(String groupId, String formId, String message,
                           BotReplier replier, String username,
                           MessageType messageType, String eventType) {
        this(groupId, formId, formId, message, replier, username, messageType, eventType);
    }

    /**
     * 兼容旧用法：仅文本回复（WSS 通道用这个）。
     * senderUid 默认等于 formId。
     */
    public BotMessageEvent(String groupId, String formId, String message, Consumer<String> replyCallback) {
        this(groupId, formId, formId, message,
                replyCallback == null ? null : (BotReplier) replyCallback::accept,
                null, null, null);
    }

    /** 富回复：Webhook 通道传入支持图片/Markdown/Ark 的回复器。 */
    public BotMessageEvent(String groupId, String formId, String message, BotReplier replier) {
        this(groupId, formId, formId, message, replier, null, null, null);
    }

    // ==================== Getter ====================

    /** 群聊 ID（平台无关字符串）。私聊时为 null。 */
    public String getGroupId() {
        return groupId;
    }

    /**
     * 获取会话 ID：群聊返回 {@code groupId}，私聊返回 {@code formId}。
     * 方便事件翻译层和附属插件无需 if/else 判断。
     */
    public String getSessionId() {
        return isGroupMessage() ? groupId : formId;
    }

    /** 协议层发送者 ID（官方=openid，OB11=QQ号字符串）。 */
    public String getFormId() {
        return formId;
    }

    /**
     * 适配器映射后的平台用户 uid，供业务层（{@code PermissionService} / 附属插件）做身份判定。
     *
     * <p>与 {@link #getFormId()} 的区别：{@code formId} 是协议层原始 ID，
     * {@code senderUid} 是经适配器映射后的契约 uid。单一协议后端下两者值相等，
     * 但语义不同，不能混用。
     */
    public String getSenderUid() {
        return senderUid;
    }

    /** 消息文本。 */
    public String getMessage() {
        return message;
    }

    /** 回复器。 */
    public BotReplier getReplier() {
        return replier;
    }

    /** 发送者昵称，可能为 null。 */
    public String getUsername() {
        return username;
    }

    /** 消息类型（GROUP / PRIVATE）。 */
    public MessageType getMessageType() {
        return messageType;
    }

    /** 原始事件类型字符串，可能为 null。业务层优先读 {@link #getMessageType()}。 */
    public String getEventType() {
        return eventType;
    }

    /** 是否为群消息。 */
    public boolean isGroupMessage() {
        return messageType == MessageType.GROUP;
    }

    /** 是否为群 @机器人 消息（eventType 含 AT 标识）。 */
    public boolean isGroupAtMessage() {
        return eventType != null && eventType.contains("AT");
    }

    /** 入站图片 URL 列表；永不为 null，无图片则为空。 */
    public java.util.List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(java.util.List<String> imageUrls) {
        this.imageUrls = (imageUrls == null) ? java.util.Collections.emptyList() : imageUrls;
    }

    // ==================== 回复方法 ====================

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

    /** 回复图片（base64 字节直传，不依赖公网/SCF）。 */
    public void replyImageData(byte[] imageBytes, String content) {
        if (replier != null) {
            replier.replyImageData(imageBytes, content);
        }
    }

    /** 回复语音（silk url；不支持的通道忽略）。 */
    public void replyVoice(String voiceUrl) {
        if (replier != null) {
            replier.replyVoice(voiceUrl);
        }
    }

    /** 回复语音（音频字节直传 silk/mp3；不支持的通道忽略）。 */
    public void replyVoiceData(byte[] audioBytes) {
        if (replier != null) {
            replier.replyVoiceData(audioBytes);
        }
    }

    /** 回复视频（mp4 url；不支持的通道降级为文本）。 */
    public void replyVideo(String videoUrl, String content) {
        if (replier != null) {
            replier.replyVideo(videoUrl, content);
        }
    }

    /** 回复 Embed（实验性；不支持的通道忽略）。 */
    public void replyEmbed(String embedJson) {
        if (replier != null) {
            replier.replyEmbed(embedJson);
        }
    }

    /** 回复 Markdown（可带键盘模板；不支持的通道降级为文本）。 */
    public void replyMarkdown(String content, String keyboardTemplateId) {
        if (replier != null) {
            replier.replyMarkdown(content, keyboardTemplateId);
        }
    }

    /** 回复 Markdown + 内联按钮键盘。 */
    public void replyKeyboard(String markdownContent, String keyboardJson) {
        if (replier != null) {
            replier.replyKeyboard(markdownContent, keyboardJson);
        }
    }

    /** 回复 Ark 卡片（不支持的通道忽略）。 */
    public void replyArk(String arkJson) {
        if (replier != null) {
            replier.replyArk(arkJson);
        }
    }
}
