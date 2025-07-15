package org.windy.xingtubot.core.api;// BotCore.java 主要内容
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.core.ws.WebSocketClientBridge;

public class BotCore {
    private final PlatformAdapter adapter;
    private final WebSocketClientBridge ws;
    private final List<Consumer<BotMessageEvent>> listeners = new CopyOnWriteArrayList<>();

    public void addMessageListener(Consumer<BotMessageEvent> listener) {
        listeners.add(listener);
    }

    public BotCore(PlatformAdapter adapter, WebSocketClientBridge ws) {
        this.adapter = adapter;
        this.ws = ws;

        ws.setListener(new WebSocketClientBridge.WebSocketListener() {
            @Override
            public void onOpen() {
                adapter.log("WebSocket已连接");
                String msg = "{\"data\":[],\"server\":\"未来之旅\",\"type\":\"白名单\"}";
                ws.send(msg);
            }

            @Override
            public void onMessage(String message) {
                adapter.log("[WS] 收到消息: " + message);
                try {
                    JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                    String guildId = json.has("guild_id") ? json.get("guild_id").getAsString() : null;
                    String formId = json.has("form_id") ? json.get("form_id").getAsString() : null;
                    String msg = json.has("msg") ? json.get("msg").getAsString() : null;

                    Consumer<String> replyCallback = (replyMsg) -> {
                        try {
                            json.addProperty("server", "未来之旅");
                            json.addProperty("reply", replyMsg);
                            ws.send(json.toString());
                        } catch (Exception e) {
                            adapter.log("回复消息失败: " + e.getMessage());
                        }
                    };

                    BotMessageEvent event = new BotMessageEvent(guildId, formId, msg, replyCallback);

                    // 分发给所有监听器
                    for (Consumer<BotMessageEvent> listener : listeners) {
                        listener.accept(event);
                    }
                } catch (Exception e) {
                    adapter.log("消息解析或分发异常: " + e.getMessage());
                }
            }

            @Override
            public void onClose() {
                adapter.log("WebSocket已断开");
            }

            @Override
            public void onError(Exception ex) {
                adapter.log("WebSocket异常: " + ex.getMessage());
            }
        });
    }

    public void start() { ws.connect(); }
    public void stop() { ws.disconnect(); }
    public void send(String msg) { ws.send(msg); }
    public WebSocketClientBridge getWsClient() { return ws; }
}