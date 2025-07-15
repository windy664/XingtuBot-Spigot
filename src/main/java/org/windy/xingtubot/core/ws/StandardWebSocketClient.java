package org.windy.xingtubot.core.ws;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.*;

public class StandardWebSocketClient extends WebSocketClient implements WebSocketClientBridge {
    private WebSocketListener listener;

    // 心跳相关
    private boolean heartbeatEnabled = false;
    private long heartbeatInterval = 30_000; // 默认30秒
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;

    public StandardWebSocketClient(String uri) throws Exception {
        super(new URI(uri));
    }

    @Override
    public void connect() {
        super.connect();
        if (heartbeatEnabled) startHeartbeat();
    }

    @Override
    public void disconnect() {
        stopHeartbeat();
        super.close();
    }

    @Override
    public boolean isOpen() { return super.isOpen(); }

    @Override
    public void send(String message) { if (isOpen()) super.send(message); }

    @Override
    public void setListener(WebSocketListener listener) { this.listener = listener; }

    @Override
    public void enableHeartbeat(long intervalMillis) {
        this.heartbeatEnabled = true;
        this.heartbeatInterval = intervalMillis;
        if (isOpen()) startHeartbeat();
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (isOpen()) {
                    this.sendPing();
                    // 可选：日志 System.out.println("[心跳] Ping sent");
                }
            } catch (Exception e) {
                // 可选：日志 System.out.println("[心跳] Ping 错误: " + e.getMessage());
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
    }

    // WebSocket 回调
    @Override
    public void onOpen(ServerHandshake handshakedata) {
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
    }

    @Override
    public void onError(Exception ex) {
        if (listener != null) listener.onError(ex);
    }
}