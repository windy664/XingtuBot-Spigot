package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.bukkit.module.aichat.AIChatModule;
import org.windy.xingtubot.bukkit.module.chatreply.ChatreplyModule;
import org.windy.xingtubot.bukkit.module.whitelist.WhitelistModule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XingtuBot extends JavaPlugin implements Listener {


    private XingtuSocketClient socketClient;
    private GuildMessageEvent lastEvent;
    private static XingtuBot instance;

    @Override
    public void onEnable() {

        instance = this;

        saveDefaultConfig(); // 初始化 config.yml
        getLogger().info("");
        getLogger().info(" _|      _|  _|_|_|  _|      _|    _|_|_|  _|_|_|_|_|  _|    _|  ");
        getLogger().info("   _|  _|      _|    _|_|    _|  _|            _|      _|    _|  ");
        getLogger().info("     _|        _|    _|  _|  _|  _|  _|_|      _|      _|    _|  ");
        getLogger().info("   _|  _|      _|    _|    _|_|  _|    _|      _|      _|    _|  ");
        getLogger().info(" _|      _|  _|_|_|  _|      _|    _|_|_|      _|        _|_|    ");
        getLogger().info("");
        getLogger().info("星途机器人插件已启动！");

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
        if (config.getBoolean("whitelist-enable", true)) {
            getLogger().info("白名单模块已开启！");
            new WhitelistModule(this);
        }
        if (config.getBoolean("chatreply-enable", true)) {
            getLogger().info("聊天回复模块已开启!");
            new ChatreplyModule(this);
        }
        if (config.getBoolean("deepseek-enable", true)) {
            // 从配置文件中获取 API 密钥
            String apiKey = getConfig().getString("deepseek-api-key");

            // 实例化 AIChatModule
            AIChatModule aiChatModule = new AIChatModule(getConfig(), getLogger(), apiKey);

            // 注册事件监听器
            getServer().getPluginManager().registerEvents(aiChatModule, this);

            getLogger().info("AI模块已启用！");
        }
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
    public static XingtuBot getInstance() {
        return instance;
    }

    public void log(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[调试模式] " + message);
        }
    }
}