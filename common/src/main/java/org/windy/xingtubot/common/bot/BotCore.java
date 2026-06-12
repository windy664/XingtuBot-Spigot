package org.windy.xingtubot.common.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.platform.PlatformAdapter;
import org.windy.xingtubot.common.ws.WebSocketClientBridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 机器人核心：连接 WebSocket，解析消息并分发给监听器。
 * 服务器标识由外部传入（不再硬编码），连接成功时的动作通过 onConnected 钩子注入。
 */
public class BotCore {
    private final PlatformAdapter adapter;
    private final WebSocketClientBridge ws;
    private final String serverName;
    private final List<Consumer<BotMessageEvent>> listeners = new CopyOnWriteArrayList<>();
    private Runnable onConnected;

    public BotCore(PlatformAdapter adapter, WebSocketClientBridge ws, String serverName) {
        this.adapter = adapter;
        this.ws = ws;
        this.serverName = serverName;

        ws.setListener(new WebSocketClientBridge.WebSocketListener() {
            @Override
            public void onOpen() {
                adapter.log("WebSocket已连接");
                if (onConnected != null) {
                    try {
                        onConnected.run();
                    } catch (Exception e) {
                        adapter.log("onConnected 回调异常: " + e.getMessage());
                    }
                }
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
                            json.addProperty("server", serverName);
                            json.addProperty("reply", replyMsg);
                            ws.send(json.toString());
                        } catch (Exception e) {
                            adapter.log("回复消息失败: " + e.getMessage());
                        }
                    };

                    BotMessageEvent event = new BotMessageEvent(guildId, formId, msg, replyCallback);
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

    /** 设置连接成功后的动作（如上报白名单） */
    public void setOnConnected(Runnable onConnected) {
        this.onConnected = onConnected;
    }

    public void addMessageListener(Consumer<BotMessageEvent> listener) {
        listeners.add(listener);
    }

    public void start() {
        ws.connect();
    }

    public void stop() {
        ws.disconnect();
    }

    public void send(String msg) {
        ws.send(msg);
    }

    public boolean isOpen() {
        return ws.isOpen();
    }

    public WebSocketClientBridge getWsClient() {
        return ws;
    }

    public String getServerName() {
        return serverName;
    }
}
