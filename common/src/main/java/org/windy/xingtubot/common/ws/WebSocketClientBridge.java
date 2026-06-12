package org.windy.xingtubot.common.ws;

/**
 * WebSocket 客户端抽象，便于上层逻辑与具体实现解耦。
 */
public interface WebSocketClientBridge {
    void connect();

    void disconnect();

    /** 断线重连（由底层实现提供） */
    void reconnect();

    boolean isOpen();

    void send(String message);

    void setListener(WebSocketListener listener);

    void enableHeartbeat(long intervalMillis);

    /** 创建默认实现，调用方无需直接依赖 java-websocket */
    static WebSocketClientBridge create(String uri) throws Exception {
        return new StandardWebSocketClient(uri);
    }

    interface WebSocketListener {
        void onOpen();

        void onMessage(String message);

        void onClose();

        void onError(Exception ex);
    }
}
