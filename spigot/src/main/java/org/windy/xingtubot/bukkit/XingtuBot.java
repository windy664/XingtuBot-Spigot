package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.bukkit.module.aichat.AIChatModule;
import org.windy.xingtubot.bukkit.module.chatreply.ChatreplyModule;
import org.windy.xingtubot.bukkit.module.whitelist.WhitelistModule;
import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.api.QqOpenApiClient;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.poll.QqWebhookBot;

public final class XingtuBot extends JavaPlugin implements Listener {

    private SpigotBotBridge botBridge;
    private QqWebhookBot webhookBot;
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
        if (webhookBot != null) {
            webhookBot.stop();
        }
        getLogger().info("插件已关闭");
    }

    private void startBot(FileConfiguration config) {
        // Webhook 长轮询模式（新机器人推荐）：启用后不再使用 WebSocket
        if (config.getBoolean("webhook-enable", false)) {
            startWebhookBot(config);
            return;
        }

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

    /** Webhook 模式：长轮询收事件 + OpenAPI 回复。 */
    private void startWebhookBot(FileConfiguration config) {
        String relayUrl = config.getString("webhook-relay-url", "");
        String appId = config.getString("openapi-app-id", "");
        String secret = config.getString("openapi-client-secret", "");
        boolean sandbox = config.getBoolean("openapi-sandbox", false);

        if (relayUrl.isEmpty() || appId.isEmpty() || secret.isEmpty()) {
            getLogger().severe("Webhook 模式需要配置 webhook-relay-url / openapi-app-id / openapi-client-secret");
            return;
        }

        try {
            QqOpenApiClient api = new QqOpenApiClient(
                    appId, secret, sandbox ? QqOpenApiClient.API_SANDBOX : QqOpenApiClient.API_PROD, null);
            webhookBot = new QqWebhookBot(new SpigotAdapter(this), relayUrl, api);
            webhookBot.addMessageListener(this::dispatchToBukkit);
            webhookBot.start();
            getLogger().info("Webhook 长轮询模式已启动（收事件走 SCF，回复走 OpenAPI）");
        } catch (Exception e) {
            getLogger().severe("启动 Webhook 模式出错：" + e.getMessage());
        }
    }

    /** 把平台无关的 BotMessageEvent 转成 Bukkit 事件，在主线程派发，复用插件事件总线。 */
    private void dispatchToBukkit(BotMessageEvent e) {
        Bukkit.getScheduler().runTask(this, () -> {
            GuildMessageEvent event = new GuildMessageEvent(
                    e.getGuildId(), e.getFormId(), e.getMessage(), e::reply);
            setLastEvent(event);
            Bukkit.getPluginManager().callEvent(event);
        });
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
