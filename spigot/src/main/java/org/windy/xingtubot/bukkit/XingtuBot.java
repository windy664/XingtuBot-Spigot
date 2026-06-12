package org.windy.xingtubot.bukkit;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.bukkit.module.aichat.AIChatModule;
import org.windy.xingtubot.bukkit.module.chatreply.ChatreplyModule;
import org.windy.xingtubot.bukkit.module.whitelist.WhitelistModule;
import org.windy.xingtubot.common.ai.AiService;

public final class XingtuBot extends JavaPlugin implements Listener {

    private SpigotBotBridge botBridge;
    private GuildMessageEvent lastEvent;
    private static XingtuBot instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        printBanner();

        FileConfiguration config = getConfig();
        startBot(config);
        registerCommands();
        enableModules(config);
    }

    @Override
    public void onDisable() {
        if (botBridge != null && botBridge.isOpen()) {
            botBridge.close();
        }
        getLogger().info("插件已关闭");
    }

    private void startBot(FileConfiguration config) {
        String wsUrl = config.getString("WebSocket", "ws://127.0.0.1:3001");
        String serverName = config.getString("server-name", "服务器");
        boolean autoOpen = config.getBoolean("AutoOpen", true);
        long heartbeatMillis = config.getInt("HeartbeatSeconds", 90) * 1000L;

        try {
            botBridge = new SpigotBotBridge(this, wsUrl, serverName, true, heartbeatMillis);
            if (autoOpen) {
                botBridge.connect();
            }
        } catch (Exception e) {
            getLogger().severe("连接 WebSocket 出错：" + e.getMessage());
        }
    }

    private void registerCommands() {
        CommandHandler handler = new CommandHandler(this);
        getCommand("xtb").setExecutor(handler);
        // TabCompleter 在所有支持的版本均可用，直接注册；
        // 原先基于 NMS 包名的版本判断在 Paper 1.20.5+ 会数组越界，已移除。
        getCommand("xtb").setTabCompleter(handler);
    }

    private void enableModules(FileConfiguration config) {
        if (config.getBoolean("whitelist-enable", true)) {
            getLogger().info("白名单模块已开启！");
            new WhitelistModule(this);
        }
        if (config.getBoolean("chatreply-enable", true)) {
            getLogger().info("聊天回复模块已开启!");
            new ChatreplyModule(this);
        }
        if (config.getBoolean("deepseek-enable", true)) {
            String apiKey = config.getString("deepseek-api-key");
            String baseUrl = config.getString("deepseek-base-url", "https://api.deepseek.com");
            AiService aiService = new AiService(apiKey, baseUrl);

            AIChatModule aiChatModule = new AIChatModule(config, getLogger(), aiService);
            getServer().getPluginManager().registerEvents(aiChatModule, this);
            getLogger().info("AI模块已启用！");
        }
    }

    private void printBanner() {
        getLogger().info("");
        getLogger().info(" _|      _|  _|_|_|  _|      _|    _|_|_|  _|_|_|_|_|  _|    _|  ");
        getLogger().info("   _|  _|      _|    _|_|    _|  _|            _|      _|    _|  ");
        getLogger().info("     _|        _|    _|  _|  _|  _|  _|_|      _|      _|    _|  ");
        getLogger().info("   _|  _|      _|    _|    _|_|  _|    _|      _|      _|    _|  ");
        getLogger().info(" _|      _|  _|_|_|  _|      _|    _|_|_|      _|        _|_|    ");
        getLogger().info("");
        getLogger().info("星途机器人插件已启动！");
    }

    public SpigotBotBridge getBotBridge() {
        return botBridge;
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
