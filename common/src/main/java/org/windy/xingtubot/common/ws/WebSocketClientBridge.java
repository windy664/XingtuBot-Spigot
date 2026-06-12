package org.windy.xingtubot.common.ws;

/**
 * WebSocket 客户端抽象，便于上层逻辑与具体实现解耦。
 */
public interface WebSocketClientBridge {
    void connect();

    void disconnect();

    boolean isOpen();

    void send(String message);

    void setListener(WebSocketListener listener);

    void enableHeartbeat(long intervalMillis);

    interface WebSocketListener {
        void onOpen();

        void onMessage(String message);

        void onClose();

        void onError(Exception ex);
    }
}
