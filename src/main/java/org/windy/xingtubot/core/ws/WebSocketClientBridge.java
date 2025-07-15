package org.windy.xingtubot.core.ws;

// core/ws/WebSocketClientBridge.java
public interface WebSocketClientBridge {
    void connect();
    void disconnect();
    boolean isOpen();
    void send(String message);
    void setListener(WebSocketListener listener);

    // 加上这一行 ↓↓↓↓↓↓↓↓↓↓↓↓
    void enableHeartbeat(long intervalMillis);

    interface WebSocketListener {
        void onOpen();
        void onMessage(String message);
        void onClose();
        void onError(Exception ex);
    }
}