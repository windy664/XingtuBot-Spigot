package org.windy.xingtubot.common.onebot;

import com.google.gson.Gson;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.messenger.MessengerConnectionState;
import org.windy.xingtubot.common.messenger.PlatformMessenger;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.IOException;
import java.util.Collections;
import java.util.function.Consumer;

/**
 * OneBot 11 平台消息适配器 —— 实现 {@link PlatformMessenger} 接口。
 *
 * <p>将 {@link OneBotEventGateway}（WS 收事件）、
 * {@link OneBotEventTranslator}（JSON → {@link BotMessageEvent}）、
 * {@link OneBotApiClient}（HTTP 发消息）三件套统一组装。
 *
 * <p>作为 {@code BotLauncher.buildOnebot11()} 的输出和 {@code LazyProactiveSender} 的注入目标。
 */
public final class OneBot11Messenger implements PlatformMessenger {

    private final OneBotEventGateway gateway;
    private final OneBotApiClient apiClient;
    private final OneBotEventTranslator translator;
    private final BotLogger logger;

    /**
     * @param wsUrl         WS 服务端地址（如 ws://127.0.0.1:3001/onebot/v11/ws）
     * @param apiUrl        HTTP API 地址（如 http://127.0.0.1:3000）
     * @param wsToken       WS 握手鉴权 token（NapCat verify-token）
     * @param httpToken     HTTP API 鉴权 token（NapCat access-token，可能与 wsToken 不同）
     * @param heartbeatMs   心跳间隔（NapCat heartInterval）
     * @param gson          Gson 实例
     * @param logger        日志
     * @param eventCallback 翻译后的 BotMessageEvent 回调
     */
    public OneBot11Messenger(String wsUrl, String apiUrl, String wsToken, String httpToken,
                             long heartbeatMs, Gson gson, BotLogger logger,
                             Consumer<BotMessageEvent> eventCallback) {
        this.logger = logger;

        // 1. HTTP API 客户端（发送）—— 使用 httpToken 鉴权
        this.apiClient = new OneBotApiClient(apiUrl, httpToken, gson, logger);

        // 2. 事件翻译器（raw JSON → BotMessageEvent）
        this.translator = new OneBotEventTranslator(this, logger, eventCallback);

        // 3. WS 网关（收事件）—— 使用 wsToken 鉴权
        this.gateway = OneBotEventGateway.create(
                wsUrl, wsToken,
                this.translator::translate, logger, heartbeatMs);
    }

    // ==================== PlatformMessenger ====================


    @Override
    public void setState(MessengerConnectionState state) {
        this.gateway.setState(state);
    }

    @Override
    public MessengerConnectionState getState() {
        return gateway.getState();
    }

    @Override
    public void sendGroupMessage(String groupId, String message) throws IOException {
        apiClient.sendGroupMessage(groupId, Collections.singletonList(MsgSegment.text(message)));
    }

    @Override
    public void sendGroupMarkdown(String groupId, String markdownContent) throws IOException {
        apiClient.sendGroupMessage(groupId, Collections.singletonList(MsgSegment.markdown(markdownContent)));
    }

    @Override
    public void sendGroupMarkdownKeyboard(String groupId, String markdownContent, String keyboardJson) throws IOException {
        // OB11 的 keyboard 实现取决于后端支持，先按 markdown 发送
        apiClient.sendGroupMessage(groupId, Collections.singletonList(MsgSegment.markdown(markdownContent)));
    }

    @Override
    public void sendPrivateMessage(String userId, String message) throws IOException {
        apiClient.sendPrivateMessage(userId, Collections.singletonList(MsgSegment.text(message)));
    }

    @Override
    public String getBotIdentifier() {
        String selfId = apiClient.getSelfId();
        return selfId != null ? selfId : "unknown";
    }

    /**
     * OB11 下 formId = QQ 号字符串，uid 约定等于该字符串，直接透传。
     */
    @Override
    public String toSenderUid(String formId) {
        return formId;
    }

    @Override
    public String fromSenderUid(String uid) {
        return uid;
    }

    @Override
    public void close() {
        gateway.stop();
    }

    // ==================== 协议层访问器 ====================

    /**
     * 事件翻译器的事件投递入口（供 BotLauncher 挂载）。
     */
    public Consumer<String> eventSink() {
        return translator::translate;
    }

    /**
     * 获取底层 API 客户端（供 OneBotReplier 等内部组件使用）。
     */
    public OneBotApiClient apiClient() {
        return apiClient;
    }

    /**
     * 获取网关引用（供状态检测使用）。
     */
    public OneBotEventGateway gateway() {
        return gateway;
    }

    /**
     * 启动 WS 连接。
     */
    public void start() {
        gateway.start();
    }
}
