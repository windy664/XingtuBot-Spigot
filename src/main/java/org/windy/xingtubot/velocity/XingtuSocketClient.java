package org.windy.xingtubot.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.windy.xingtubot.velocity.event.GuildMessageEvent;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class XingtuSocketClient extends WebSocketClient {

    private final VelocityPlugin plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final boolean debug;
    private final boolean reconnect;
    private final int maxReconnect;
    private final int taskTimeout;
    private final int maxActiveCount;
    private final Scheduler scheduler;

    private int reconnectAttempts = 0;
    private ScheduledTask heartbeatTask;

    public XingtuSocketClient(String serverUri, VelocityPlugin plugin,
                              boolean debug, boolean reconnect,
                              int maxReconnect, int taskTimeout,
                              int maxActiveCount) throws Exception {
        super(new URI(serverUri));
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
        this.debug = debug;
        this.reconnect = reconnect;
        this.maxReconnect = maxReconnect;
        this.taskTimeout = taskTimeout;
        this.maxActiveCount = maxActiveCount;
        this.scheduler = server.getScheduler();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        reconnectAttempts = 0;
        logger.info("已连接星途机器人框架");

        // 启动心跳
        heartbeatTask = scheduler.buildTask(plugin, () -> {
            if (this.isOpen()) {
                try {
                    this.sendPing();
                    log("[心跳] 已发送 WebSocket Ping 帧");
                } catch (Exception e) {
                    log("发送 Ping 失败: " + e.getMessage());
                }
            }
        }).repeat(30, TimeUnit.SECONDS).schedule();

    }

    @Override
    public void onMessage(String message) {
        scheduler.buildTask(plugin, () -> {
            try {
                log("[调试模式] 收到消息: " + message);
                JsonObject incoming = JsonParser.parseString(message).getAsJsonObject();

                String msg = incoming.get("msg").getAsString();
                String guildId = incoming.get("guild_id").getAsString();
                String formId = incoming.get("form_id").getAsString();

                Consumer<String> replyCallback = (replyMsg) -> {
                    try {
                        incoming.addProperty("reply", replyMsg);
                        incoming.addProperty("server", "未来之旅"); // 使用配置的服务器名
                        this.send(incoming.toString());
                    } catch (Exception e) {
                        logger.warn("返回至框架出现错误: " + e.getMessage());
                    }
                };

                // 创建并发布自定义事件
                GuildMessageEvent event = new GuildMessageEvent(guildId, formId, msg, replyCallback);

                // 保存事件到插件实例（用于/xtb reply命令）
                plugin.setLastEvent(event);

                // 触发事件（异步执行）
                plugin.getServer().getEventManager()
                        .fire(event)
                        .thenAccept(e -> {
                            // 检查是否有事件处理程序执行了回复
                            if (!e.isHandled()) {
                                plugin.getLogger().debug("收到群消息但未处理");
                            }
                        });

            } catch (Exception e) {
                logger.warn("处理消息异常: " + e.getMessage());
            }
        }).schedule();
    }

    @Override
    public void onWebsocketPong(org.java_websocket.WebSocket conn, Framedata f) {
        super.onWebsocketPong(conn, f);
        log("[心跳] 收到 WebSocket Pong 帧");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.warn("WebSocket 已断开连接: " + reason);
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }

        if (reconnect && reconnectAttempts < maxReconnect) {
            reconnectAttempts++;
            logger.info("正在尝试第 " + reconnectAttempts + " 次重连...");
            scheduler.buildTask(plugin, () -> {
                try {
                    this.reconnect();
                } catch (Exception e) {
                    logger.error("重连失败: " + e.getMessage());
                }
            }).delay(5, TimeUnit.SECONDS).schedule();
        }
    }

    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket 出现错误: " + ex.getMessage());
    }

    public void log(String message) {
        if (debug) {
            logger.info(message);
        }
    }
}