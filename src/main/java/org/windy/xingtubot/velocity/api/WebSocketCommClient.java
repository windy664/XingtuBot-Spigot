package org.windy.xingtubot.velocity.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 通用WebSocket通信接口
 */
public abstract class WebSocketCommClient extends WebSocketClient {

    protected final Logger logger;
    protected final SchedulerFacade scheduler;
    protected final Set<Consumer<String>> messageHandlers = new HashSet<>();
    protected ScheduledTask heartbeatTask;

    // WebSocket配置
    private boolean debug;
    private boolean reconnect;
    private int maxReconnect;
    private int reconnectAttempts = 0;
    private final long reconnectDelay = 5; // 秒
    private final long heartbeatInterval = 30; // 秒

    protected WebSocketCommClient(String serverUri, Logger logger,
                                  boolean debug, boolean reconnect,
                                  int maxReconnect) throws Exception {
        super(new URI(serverUri));
        this.logger = logger;
        this.scheduler = new SchedulerFacade();
        this.debug = debug;
        this.reconnect = reconnect;
        this.maxReconnect = maxReconnect;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        reconnectAttempts = 0;
        log("WebSocket连接已建立");

        // 启动心跳检测
        startHeartbeat();
    }

    private void startHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }

        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (this.isOpen()) {
                try {
                    this.sendPing();
                    log("发送心跳PING");
                } catch (Exception e) {
                    log("发送心跳失败: " + e.getMessage());
                }
            }
        }, 0, heartbeatInterval, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(String message) {
        // 分发消息给所有处理器
        for (Consumer<String> handler : messageHandlers) {
            try {
                scheduler.execute(() -> handler.accept(message));
            } catch (Exception e) {
                log("消息处理器出错: " + e.getMessage());
            }
        }
    }

    @Override
    public void onWebsocketPong(org.java_websocket.WebSocket conn, Framedata f) {
        super.onWebsocketPong(conn, f);
        log("收到心跳PONG");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log("WebSocket已断开: " + reason);
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }

        // 重连逻辑
        if (reconnect && reconnectAttempts < maxReconnect) {
            reconnectAttempts++;
            log("尝试第 " + reconnectAttempts + " 次重连...");
            scheduler.scheduleDelayed(this::reconnect, reconnectDelay, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onError(Exception ex) {
        log("WebSocket错误: " + ex.getMessage());
    }

    /**
     * 添加消息处理器
     */
    public void addMessageHandler(Consumer<String> handler) {
        messageHandlers.add(handler);
    }

    /**
     * 移除消息处理器
     */
    public void removeMessageHandler(Consumer<String> handler) {
        messageHandlers.remove(handler);
    }

    /**
     * 处理JSON消息
     */
    protected <T> T parseJsonMessage(String message, Function<JsonObject, T> parser) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            return parser.apply(json);
        } catch (Exception e) {
            log("JSON解析失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 发送JSON消息
     */
    public CompletableFuture<Void> sendJson(JsonObject json) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        scheduler.execute(() -> {
            try {
                send(json.toString());
                future.complete(null);
            } catch (Exception e) {
                log("发送消息失败: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * 调试日志
     */
    protected void log(String message) {
        if (debug) {
            logger.info("[调试] " + message);
        }
    }

    /**
     * 调度器接口
     */
    public interface SchedulerFacade {
        void execute(Runnable task);
        ScheduledTask scheduleDelayed(Runnable task, long delay, TimeUnit unit);
        ScheduledTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit);
    }
}