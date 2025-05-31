package org.windy.xingtubot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.windy.xingtubot.event.GuildMessageEvent;

import java.io.File;
import java.net.URI;
import java.util.function.Consumer;

public class XingtuSocketClient extends WebSocketClient {

    private final Plugin plugin;
    private final boolean debug;
    private final boolean reconnect;
    private final int maxReconnect;
    private final int taskTimeout;
    private final int maxActiveCount;

    private int reconnectAttempts = 0;

    public XingtuSocketClient(String serverUri, Plugin plugin,
                              boolean debug, boolean reconnect,
                              int maxReconnect, int taskTimeout,
                              int maxActiveCount) throws Exception {
        super(new URI(serverUri));
        this.plugin = plugin;
        this.debug = debug;
        this.reconnect = reconnect;
        this.maxReconnect = maxReconnect;
        this.taskTimeout = taskTimeout;
        this.maxActiveCount = maxActiveCount;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        reconnectAttempts = 0;
        plugin.getLogger().info("已连接星途机器人框架");

        // 启动心跳
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (this.isOpen()) {
                try {
                    this.sendPing();
                    log("[心跳] 已发送 WebSocket Ping 帧");
                } catch (Exception e) {
                    log("发送 Ping 失败：" + e.getMessage());
                }
            }
        }, 0L, 20L * 30); // 每 30 秒

        // 加载白名单 JSON 并发送给框架
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File file = new File(plugin.getDataFolder(), "whitelist.json");
                if (!file.exists()) {
                    plugin.getLogger().warning("获取白名单失败。");
                    return;
                }

                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                JsonParser parser = new JsonParser();
                JsonObject wrapper = new JsonObject();
                wrapper.add("data", parser.parse(content).getAsJsonArray());
                wrapper.addProperty("server", plugin.getConfig().getString("server-name", "服务器")); // TODO: 可改为读取配置
                wrapper.addProperty("type", "白名单");

                this.send(wrapper.toString());
                plugin.getLogger().info("已发送白名单数据至框架");
            } catch (Exception e) {
                plugin.getLogger().severe("发送白名单时出错：" + e.getMessage());
            }
        });
    }

    @Override
    public void onMessage(String message) {

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                log("[调试模式] 收到消息：" + message);



                JsonObject incoming = JsonParser.parseString(message).getAsJsonObject();

                String msg = incoming.get("msg").getAsString();
                String guildId = incoming.get("guild_id").getAsString();
                String formId = incoming.get("form_id").getAsString();



                Consumer<String> replyCallback = (replyMsg) -> {
                    try {
                        incoming.addProperty("reply", replyMsg);

                        incoming.addProperty("server", plugin.getConfig().getString("server-name", "服务器")); // 添加服务器标识字段

                        this.send(incoming.toString());
                    } catch (Exception e) {
                        plugin.getLogger().warning("返回至框架出现错误：" + e.getMessage());
                    }
                };

                GuildMessageEvent event = new GuildMessageEvent(guildId, formId, msg, replyCallback);

                ((XingtuBot) plugin).setLastEvent(event); // 保存事件

                Bukkit.getPluginManager().callEvent(event);

                // Bukkit.broadcastMessage("来自群 " + guildId + " 的 " + formId + "： " + msg);

            } catch (Exception e) {
                plugin.getLogger().warning("处理消息异常" + e.getMessage());
            }
        });
    }

    @Override
    public void onWebsocketPong(org.java_websocket.WebSocket conn, Framedata f) {
        super.onWebsocketPong(conn, f);
        log("[心跳] 收到 WebSocket Pong 帧");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        plugin.getLogger().warning("WebSocket 已断开连接: " + reason);

        if (reconnect && reconnectAttempts < maxReconnect) {
            reconnectAttempts++;
            plugin.getLogger().info("正在尝试第 " + reconnectAttempts + " 次重连...");
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                try {
                    this.reconnect();
                } catch (Exception e) {
                    plugin.getLogger().severe("重连失败：" + e.getMessage());
                }
            }, 20L * 5); // 5 秒后尝试重连
        }
    }

    @Override
    public void onError(Exception ex) {
        plugin.getLogger().severe("WebSocket 出现错误：" + ex.getMessage());
    }

    public void log(String message) {
        if (debug) {
            plugin.getLogger().info(message);
        }
    }
}