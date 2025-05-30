package org.windy.xingtubot;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.event.GuildMessageEvent;
import org.windy.xingtubot.module.chatreply.ChatreplyModule;
import org.windy.xingtubot.module.whitelist.WhitelistModule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XingtuBot extends JavaPlugin implements Listener {


    private XingtuSocketClient socketClient;
    private GuildMessageEvent lastEvent;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // 初始化 config.yml
        getLogger().info("星途Bukkit插件启动...");

        FileConfiguration config = getConfig();
        boolean debug = config.getBoolean("debug", false);
        String wsUrl = config.getString("WebSocket", "ws://127.0.0.1:3001");
        boolean autoOpen = config.getBoolean("AutoOpen", true);
        boolean reconnect = config.getBoolean("Reconnect", true);
        int maxReconnect = config.getInt("MaxReconnect", 5);
        int taskTimeout = config.getInt("TaskTimeout", 10);
        int maxActiveCount = config.getInt("MaxActiveCount", 10);

        try {
            socketClient = new XingtuSocketClient(wsUrl, this, debug, reconnect, maxReconnect, taskTimeout, maxActiveCount);
            if (autoOpen) {
                socketClient.connect();
            }
        } catch (Exception e) {
            getLogger().severe("连接 WebSocket 出错：" + e.getMessage());
        }
        getCommand("xtb").setExecutor(new CommandHandler(this));

        //版本验证，是否开启版本补全
        String nmsPackageName = Bukkit.getServer().getClass().getPackage().getName();
        String versionSegment = nmsPackageName.split("\\.")[3]; // 获取版本部分，如v1_13_R1
        Pattern pattern = Pattern.compile("v(\\d+)_R");
        Matcher matcher = pattern.matcher(versionSegment);
        if (matcher.find()) {
            int majorVersion = Integer.parseInt(matcher.group(1));
            if (majorVersion >= 13) {
                getCommand("xtb").setTabCompleter(new CommandHandler(this));
            }
        }

        new WhitelistModule(this);
        new ChatreplyModule(this);


    }

    @Override
    public void onDisable() {
        if (socketClient != null && socketClient.isOpen()) {
            socketClient.close();
        }
        getLogger().info("插件已关闭");
    }
    public XingtuSocketClient getSocketClient() {
        return socketClient;
    }

    public void setSocketClient(XingtuSocketClient socketClient) {
        this.socketClient = socketClient;
    }

    public GuildMessageEvent getLastEvent() {
        return lastEvent;
    }
    public void setLastEvent(GuildMessageEvent event) {
        this.lastEvent = event;
    }

}