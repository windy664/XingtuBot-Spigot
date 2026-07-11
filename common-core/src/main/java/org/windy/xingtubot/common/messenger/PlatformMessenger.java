package org.windy.xingtubot.common.messenger;

import java.io.IOException;

/**
 * 平台消息适配器接口 —— 承载「群聊 id → 实际协议发送」的职责。
 *
 * <p>两套实现：
 * <ul>
 *   <li>{@link OfficialBotMessenger} —— 包装 {@code QqOpenApiClient}（QQ 官方协议）</li>
 *   <li>{@code OneBot11Messenger} —— 包装 {@code OneBotApiClient}（OneBot 11 协议）</li>
 * </ul>
 *
 * <p>业务层（{@code XingtuBotServiceImpl} / {@code ProactiveSender} / {@code HandlerRegistry}）
 * 通过本接口与底层协议解耦，不再直接依赖 {@code QqOpenApiClient}。
 */
public interface PlatformMessenger {

    /**
     * 设置当前的连接状态
     *
     * @param state
     */
    void setState(MessengerConnectionState state);

    /**
     * 当前连接状态 —— 给 {@code LazyProactiveSender.isReady()} 等使用。
     */
    MessengerConnectionState getState();

    /**
     * 主动发送群消息（纯文本）。
     *
     * @param groupId 平台无关的群聊 ID（官方 bot = group_openid，OB11 = 群号字符串）
     * @param message 消息文本
     * @throws IOException 发送失败时抛出
     */
    void sendGroupMessage(String groupId, String message) throws IOException;

    /**
     * 主动发送群 Markdown 消息。
     * 部分协议（如 OB11）可降级为文本。
     *
     * @param groupId         平台无关的群聊 ID
     * @param markdownContent Markdown 内容
     * @throws IOException 发送失败时抛出
     */
    void sendGroupMarkdown(String groupId, String markdownContent) throws IOException;

    /**
     * 主动发送群 Markdown 消息 + 自定义内联按钮键盘。
     *
     * @param groupId         平台无关的群聊 ID
     * @param markdownContent Markdown 内容
     * @param keyboardJson    键盘 JSON 字符串
     * @throws IOException 发送失败时抛出
     */
    void sendGroupMarkdownKeyboard(String groupId, String markdownContent, String keyboardJson) throws IOException;

    /**
     * 主动发送单聊消息（纯文本）。
     *
     * @param userId  平台无关的用户 ID
     * @param message 消息文本
     * @throws IOException 发送失败时抛出
     */
    void sendPrivateMessage(String userId, String message) throws IOException;

    /**
     * 获取当前 bot 的标识符。
     * <ul>
     *   <li>官方 bot = AppId</li>
     *   <li>OB11 = 机器人 QQ 号（字符串）</li>
     * </ul>
     */
    String getBotIdentifier();

    /**
     * 将协议层原始发送者 ID（{@code formId} 取值）映射为平台用户 uid。
     *
     * <p>供 {@code BotMessageEvent.senderUid} 赋值使用。当前双端实现均为直接透传，
     * 但接口形态为多协议（钉钉/飞书）预留带平台前缀 uuid 的能力。
     *
     * @param formId 协议层原始发送者 ID
     * @return 平台用户 uid
     */
    String toSenderUid(String formId);

    /**
     * 反向解析 uid → 协议层原始 ID（发送主动消息时需要）。
     *
     * @param uid 平台用户 uid
     * @return 协议层原始发送者 ID
     */
    String fromSenderUid(String uid);

    /**
     * 关闭并释放资源（WS/HTTP 连接等）。
     */
    void close();
}
