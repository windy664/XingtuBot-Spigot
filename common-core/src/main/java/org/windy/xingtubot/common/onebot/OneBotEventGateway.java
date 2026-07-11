package org.windy.xingtubot.common.onebot;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.windy.xingtubot.common.messenger.MessengerConnectionState;
import org.windy.xingtubot.common.platform.BotLogger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * OneBot 11 正向 WebSocket 事件网关。
 *
 * <p>以 WS 客户端身份主动连接 NapCat（WS 服务端），接收事件 JSON 后回调给业务层。
 *
 * <p>心跳方向（与 QQ 官方相反）：
 * <ul>
 *   <li>NapCat 服务端按 {@code heartInterval} 发 PING 帧</li>
 *   <li>插件收到 PING 后立即回复 PONG 帧</li>
 *   <li>插件<strong>不主动发送心跳包</strong></li>
 * </ul>
 *
 * <p>断连后按指数退避重连：[1, 2, 4, 8, 16, 30, 60] 秒。
 */
public final class OneBotEventGateway extends WebSocketClient {

    private static final int[] RECONNECT_DELAYS = {1, 2, 4, 8, 16, 30, 60};
    private static final int MAX_RECONNECT = RECONNECT_DELAYS.length;

    private final Consumer<String> eventCallback;
    private final BotLogger logger;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OneBotWS-Reconnect");
        t.setDaemon(true);
        return t;
    });

    // 连接状态
    private volatile MessengerConnectionState state = MessengerConnectionState.DISCONNECTED;
    // PONG 超时检测调度
    private final long pingTimeoutMs;

    /**
     * @param serverUri           NapCat WS 服务端地址
     * @param accessToken         Bearer token（可选）
     * @param eventCallback       收到事件 JSON 的回调
     * @param logger              日志
     * @param heartbeatIntervalMs NapCat 心跳间隔（用于计算 PONG 超时）
     */
    public OneBotEventGateway(URI serverUri, String accessToken,
                              Consumer<String> eventCallback, BotLogger logger,
                              long heartbeatIntervalMs) {
        super(serverUri);
        this.eventCallback = eventCallback;
        this.logger = logger;
        this.pingTimeoutMs = (long) (heartbeatIntervalMs * 1.5);
        setConnectionLostTimeout((int) (pingTimeoutMs / 1000));
        // OneBot 11 正向 WS 鉴权：通过 Authorization 请求头携带 Bearer token（与 OneBotApiClient 一致）
        if (accessToken != null && !accessToken.isEmpty()) {
            this.addHeader("Authorization", "Bearer " + accessToken);
        }
    }

    /**
     * 便捷构造：从字符串 URI 创建。
     */
    public static OneBotEventGateway create(String wsUrl, String accessToken,
                                            Consumer<String> eventCallback, BotLogger logger,
                                            long heartbeatIntervalMs) {
        try {
            return new OneBotEventGateway(new URI(wsUrl), accessToken, eventCallback, logger, heartbeatIntervalMs);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("无效的 WebSocket URL: " + wsUrl, e);
        }
    }

    // ==================== 状态查询 ====================

    public MessengerConnectionState getState() {
        return state;
    }

    public void setState(MessengerConnectionState state) {
        this.state = state;
    }

    // ==================== WebSocketClient 回调 ====================

    @Override
    public void onOpen(ServerHandshake handshake) {
        log("[INFO] 正向 WS 连接已建立");
        state = MessengerConnectionState.READY;
        reconnectAttempt.set(0);
    }

    @Override
    public void onMessage(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return;

        // 心跳检测：NapCat 发的 PING 帧（纯文本 "PING"）
        if ("PING".equals(rawJson) || "ping".equalsIgnoreCase(rawJson.trim())) {
            handlePingFrame();
            return;
        }

        // NapCat 主动推送的 heartbeat 元事件（meta_event_type=heartbeat）——心跳存活信号，无需业务处理
        if (isHeartbeatMetaEvent(rawJson)) {
            return;
        }

        // 事件帧：透传给业务层
        if (eventCallback != null) {
            eventCallback.accept(rawJson);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log("[WARN] WS 连接已关闭: code=" + code + " reason=" + reason + " remote=" + remote);
        state = MessengerConnectionState.DISCONNECTED;
        scheduleReconnect();
    }

    @Override
    public void onError(Exception ex) {
        log("[ERROR] WS 连接异常: " + ex.getMessage());
        if (state == MessengerConnectionState.READY) {
            state = MessengerConnectionState.DISCONNECTED;
        }
    }

    // ==================== 心跳 ====================

    /**
     * 处理来自 NapCat 服务端的 PING 帧。
     *
     * <p>NapCat 按 heartInterval 发 PING，插件必须立即回 PONG。
     * 这是 OneBot 11 协议与 QQ 官方相反的心跳方向。
     */
    private void handlePingFrame() {
        send("PONG");
        log("[DEBUG] 收到 PING → 已回 PONG");
    }

    // ==================== 重连 ====================

    private void scheduleReconnect() {
        int attempt = reconnectAttempt.getAndIncrement();
        if (attempt >= MAX_RECONNECT) {
            log("[ERROR] 已达最大重连次数 (" + MAX_RECONNECT + ")，停止重连");
            state = MessengerConnectionState.DISCONNECTED;
            return;
        }
        int delay = RECONNECT_DELAYS[attempt];
        log("[INFO] " + delay + " 秒后重连 (第 " + (attempt + 1) + "/" + MAX_RECONNECT + " 次)");
        state = MessengerConnectionState.RECONNECTING;
        scheduler.schedule(() -> {
            try {
                state = MessengerConnectionState.CONNECTING;
                reconnectBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("[WARN] 重连被中断");
            }
        }, delay, TimeUnit.SECONDS);
    }

    // ==================== 启动/停止 ====================

    /**
     * 启动 WS 连接（首次连接阻塞直到成功或超时）。
     */
    public void start() {
        log("[INFO] 正在连接 OneBot 11 WS 服务端: " + getURI());
        state = MessengerConnectionState.CONNECTING;
        try {
            connectBlocking(10000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("[ERROR] 连接被中断");
            state = MessengerConnectionState.DISCONNECTED;
        }
    }

    /**
     * 停止 WS 连接并关闭调度器。
     */
    public void stop() {
        log("[INFO] 正在关闭 WS 连接...");
        scheduler.shutdownNow();
        try {
            closeBlocking();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        state = MessengerConnectionState.DISCONNECTED;
    }

    // ==================== 内部 ====================

    /**
     * 判断是否为 heartbeat 元事件（NapCat 主动推送的心跳存活信号，无需业务处理）。
     *
     * <p>典型载荷：{"post_type":"meta_event","meta_event_type":"heartbeat",...}
     */
    private boolean isHeartbeatMetaEvent(String rawJson) {
        int idx = rawJson.indexOf("heartbeat");
        return idx > 0 && rawJson.lastIndexOf("meta_event_type", idx) >= 0;
    }

    private void log(String msg) {
        if (logger != null) {
            logger.info("[OneBotWS] " + msg);
        }
    }
}
