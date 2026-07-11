package org.windy.xingtubot.common.messenger;

import org.windy.xingtubot.common.api.QqOpenApiClient;

import java.io.IOException;

/**
 * {@link PlatformMessenger} 的 QQ 官方 bot 实现。
 *
 * <p>包装 {@link QqOpenApiClient}，将官方 OpenAPI 调用封装为平台无关的消息发送接口。
 *
 * <p>连接状态由外部（{@code BotLauncher} / {@code QQGatewayClient}）驱动，
 * 通过 {@link #setState(MessengerConnectionState)} 更新。
 */
public class OfficialBotMessenger implements PlatformMessenger {

    private final QqOpenApiClient apiClient;
    private volatile MessengerConnectionState state = MessengerConnectionState.DISCONNECTED;

    public OfficialBotMessenger(QqOpenApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /** 更新连接状态（由启动器/网关客户端在连接状态变化时调用）。 */
    @Override
    public void setState(MessengerConnectionState state) {
        this.state = state;
    }

    /** 获取底层 OpenAPI 客户端（供 OpenApiBotReplier 等内部使用）。 */
    public QqOpenApiClient getApiClient() {
        return apiClient;
    }

    @Override
    public MessengerConnectionState getState() {
        return state;
    }

    @Override
    public void sendGroupMessage(String groupId, String message) throws IOException {
        apiClient.sendProactiveGroupMessage(groupId, message);
    }

    @Override
    public void sendGroupMarkdown(String groupId, String markdownContent) throws IOException {
        apiClient.sendProactiveGroupMarkdown(groupId, markdownContent);
    }

    @Override
    public void sendGroupMarkdownKeyboard(String groupId, String markdownContent, String keyboardJson) throws IOException {
        apiClient.sendProactiveGroupMarkdown(groupId, markdownContent, keyboardJson);
    }

    @Override
    public void sendPrivateMessage(String userId, String message) throws IOException {
        apiClient.sendProactiveC2CMessage(userId, message);
    }

    @Override
    public String getBotIdentifier() {
        return apiClient.getAppId();
    }

    /**
     * 官方 bot 下，{@code formId} 本身就是 openid，直接透传。
     */
    @Override
    public String toSenderUid(String formId) {
        return formId;
    }

    /**
     * 官方 bot 下，{@code uid} 就是 openid，直接透传。
     */
    @Override
    public String fromSenderUid(String uid) {
        return uid;
    }

    @Override
    public void close() {
        setState(MessengerConnectionState.DISCONNECTED);
    }
}
