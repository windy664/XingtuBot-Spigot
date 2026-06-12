package org.windy.xingtubot.bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.bot.BotCore;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.ws.WebSocketClientBridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 把 common 的 BotCore 接到 Spigot：
 * - 连接成功时上报白名单 JSON
 * - 收到消息转成 Bukkit GuildMessageEvent 在主线程派发，复用插件内的事件总线
 */
public class SpigotBotBridge {
    private final XingtuBot plugin;
    private final BotCore botCore;
    // 仅依赖接口，避免把 java-websocket 暴露到 spigot 编译期
    private final WebSocketClientBridge wsClient;

    public SpigotBotBridge(XingtuBot plugin, String wsUrl, String serverName,
                           boolean heartbeat, long heartbeatMillis) throws Exception {
        this.plugin = plugin;
        this.wsClient = WebSocketClientBridge.create(wsUrl);
        if (heartbeat) {
            wsClient.enableHeartbeat(heartbeatMillis);
        }
        this.botCore = new BotCore(new SpigotAdapter(plugin), wsClient, serverName);
        botCore.setOnConnected(this::sendWhitelistPayload);
        botCore.addMessageListener(this::dispatch);
    }

    private void dispatch(BotMessageEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            GuildMessageEvent event = new GuildMessageEvent(
                    e.getGuildId(), e.getFormId(), e.getMessage(), e::reply);
            plugin.setLastEvent(event);
            Bukkit.getPluginManager().callEvent(event);
        });
    }

    private void sendWhitelistPayload() {
        plugin.getLogger().info("已连接星途机器人框架");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File file = new File(plugin.getDataFolder(), "whitelist.json");
                if (!file.exists()) {
                    plugin.getLogger().warning("获取白名单失败。");
                    return;
                }
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JsonObject wrapper = new JsonObject();
                wrapper.add("data", JsonParser.parseString(content).getAsJsonArray());
                wrapper.addProperty("server", botCore.getServerName());
                wrapper.addProperty("type", "白名单");
                botCore.send(wrapper.toString());
                plugin.getLogger().info("已发送白名单数据至框架");
            } catch (Exception ex) {
                plugin.getLogger().severe("发送白名单时出错：" + ex.getMessage());
            }
        });
    }

    public void connect() {
        botCore.start();
    }

    public void close() {
        botCore.stop();
    }

    public boolean isOpen() {
        return botCore.isOpen();
    }

    /** 供 /xtb connect 手动重连 */
    public void reconnect() {
        wsClient.reconnect();
    }
}
