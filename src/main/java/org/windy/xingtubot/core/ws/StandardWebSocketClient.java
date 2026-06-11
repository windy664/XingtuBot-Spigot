package org.windy.xingtubot.core.ws;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.*;

/**
 * 增强版 WebSocket 客户端：
 * ✅ 自动心跳（Ping）
 * ✅ 自动重连（指数退避）
 * ✅ 安全关闭任务
 */
public class StandardWebSocketClient extends WebSocketClient implements WebSocketClientBridge {
    private WebSocketListener listener;

    // === 心跳相关 ===
    private boolean heartbeatEnabled = false;
    private long heartbeatInterval = 30_000; // 默认 30 秒
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;

    // === 自动重连相关 ===
    private final int maxReconnectAttempts = 10;
    private int reconnectAttempts = 0;
    private final long reconnectDelayMillis = 5_000; // 初始重连间隔
    private volatile boolean manualDisconnect = false;

    public StandardWebSocketClient(String uri) throws Exception {
        super(new URI(uri));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WsClient-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    // ========= 基本接口 =========
    @Override
    public void connect() {
        manualDisconnect = false;
        reconnectAttempts = 0;
        super.connect();
    }

    @Override
    public void disconnect() {
        manualDisconnect = true;
        stopHeartbeat();
        super.close();
    }

    @Override
    public boolean isOpen() {
        return super.isOpen();
    }

    @Override
    public void send(String message) {
        if (isOpen()) super.send(message);
    }

    @Override
    public void setListener(WebSocketListener listener) {
        this.listener = listener;
    }

    @Override
    public void enableHeartbeat(long intervalMillis) {
        this.heartbeatEnabled = true;
        this.heartbeatInterval = intervalMillis;
        if (isOpen()) startHeartbeat();
    }

    // ========= 心跳 =========
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (isOpen()) {
                    sendPing();
                } else {
                    // 如果掉线则尝试重连
                    tryReconnect();
                }
            } catch (Exception e) {
                if (listener != null)
                    listener.onError(new Exception("心跳错误: " + e.getMessage(), e));
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(true);
        }
    }

    // ========= 自动重连逻辑 =========
    private void tryReconnect() {
        if (manualDisconnect) return;
        if (reconnectAttempts >= maxReconnectAttempts) {
            if (listener != null) {
                listener.onError(new Exception("重连已达最大次数，停止。"));
            }
            return;
        }

        reconnectAttempts++;
        long delay = reconnectDelayMillis * reconnectAttempts; // 简单退避
        scheduler.schedule(() -> {
            if (!isOpen()) {
                if (listener != null)
                    listener.onError(new Exception("尝试第 " + reconnectAttempts + " 次重连..."));
                try {
                    super.reconnect(); // 调用库内重连方法
                } catch (Exception e) {
                    if (listener != null) listener.onError(e);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ========= WebSocket 回调 =========
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        reconnectAttempts = 0;
        if (listener != null) listener.onOpen();
        if (heartbeatEnabled) startHeartbeat();
    }

    @Override
    public void onMessage(String message) {
        if (listener != null) listener.onMessage(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        stopHeartbeat();
        if (listener != null) listener.onClose();
        if (!manualDisconnect) tryReconnect();
    }

    @Override
    public void onError(Exception ex) {
        if (listener != null) listener.onError(ex);
        tryReconnect();
    }
}