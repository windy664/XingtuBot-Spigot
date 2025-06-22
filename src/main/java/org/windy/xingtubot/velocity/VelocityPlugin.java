package org.windy.xingtubot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.command.CommandManager;
import org.slf4j.Logger;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.windy.xingtubot.velocity.event.GuildMessageEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Plugin(id = "xingtubot", name = "XingtuBot", version = "0.1.0-SNAPSHOT",
        authors = {"Me"}, url = "https://example.org", description = "星途机器人插件")
public class VelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final CommandManager commandManager;
    private XingtuSocketClient socketClient;
    private ConfigurationNode config;

    @Inject
    public VelocityPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
        this.commandManager = server.getCommandManager();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("");
        logger.info(" _|      _|  _|_|_|  _|      _|    _|_|_|  _|_|_|_|_|  _|    _|  ");
        logger.info("   _|  _|      _|    _|_|    _|  _|            _|      _|    _|  ");
        logger.info("     _|        _|    _|  _|  _|  _|  _|_|      _|      _|    _|  ");
        logger.info("   _|  _|      _|    _|    _|_|  _|    _|      _|      _|    _|  ");
        logger.info(" _|      _|  _|_|_|  _|      _|    _|_|_|      _|        _|_|    ");
        logger.info("");
        logger.info("星途机器人插件已启动！");

        // 加载配置
        loadConfig();

        // 初始化WebSocket客户端
        initWebSocket();

        // 注册命令
        CommandMeta meta = commandManager.metaBuilder("xtb")
                .plugin(this)
                .build();

        // 注册命令
        commandManager.register(meta, new VCCommandHandler(this));

        // 初始化模块
        initModules();
    }

    public void loadConfig() {
        try {
            // 创建插件数据目录
            Path configPath = Paths.get("plugins", "XingtuBot", "config.yml");
            File configFile = configPath.toFile();
            File configDir = configFile.getParentFile();

            // 确保目录存在
            if (!configDir.exists() && !configDir.mkdirs()) {
                logger.error("无法创建插件目录: " + configDir.getAbsolutePath());
                return;
            }

            // 如果配置文件不存在，创建默认配置
            if (!configFile.exists()) {
                try {
                    // 从资源加载默认配置
                    InputStream defaultConfig = getClass().getResourceAsStream("/config.yml");
                    if (defaultConfig != null) {
                        Files.copy(defaultConfig, configPath, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("已创建默认配置文件");
                    } else {
                        // 创建空配置文件
                        configFile.createNewFile();
                        logger.warn("缺少默认配置文件，已创建空文件");
                    }
                } catch (IOException e) {
                    logger.error("创建配置文件失败: " + e.getMessage());
                }
            }

            // 加载配置文件
            YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder()
                    .setPath(configPath)
                    .build();

            config = loader.load();
        } catch (Exception e) {
            logger.error("加载配置文件失败: " + e.getMessage());
        }
    }
    private void initWebSocket() {
        boolean debug = config.getNode("debug").getBoolean(false);
        String wsUrl = config.getNode("WebSocket").getString("ws://127.0.0.1:3001");
        boolean autoOpen = config.getNode("AutoOpen").getBoolean(true);
        boolean reconnect = config.getNode("Reconnect").getBoolean(true);
        int maxReconnect = config.getNode("MaxReconnect").getInt(5);
        int taskTimeout = config.getNode("TaskTimeout").getInt(10);
        int maxActiveCount = config.getNode("MaxActiveCount").getInt(10);

        try {
            socketClient = new XingtuSocketClient(
                    wsUrl,
                    this,
                    debug,
                    reconnect,
                    maxReconnect,
                    taskTimeout,
                    maxActiveCount
            );

            if (autoOpen) {
                socketClient.connect();
            }
        } catch (Exception e) {
            logger.error("连接 WebSocket 出错: " + e.getMessage());
        }
    }

    private void initModules() {
        if (config.getNode("whitelist-enable").getBoolean(false)) {
            logger.info("白名单模块已开启！");
            // 初始化白名单模块（需要实现）
        }

        if (config.getNode("chatreply-enable").getBoolean(false)) {
            logger.info("聊天回复模块已开启!");
            // 初始化聊天回复模块（需要实现）
        }

        if (config.getNode("deepseek-enable").getBoolean(false)) {
            String apiKey = config.getNode("deepseek-api-key").getString();
            // 初始化AI模块（需要实现）
            logger.info("AI模块已启用！");
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (socketClient != null && socketClient.isOpen()) {
            socketClient.close();
        }
        logger.info("插件已关闭");
    }

    // Getter 方法
    public ProxyServer getServer() { return server; }
    public Logger getLogger() { return logger; }
    public XingtuSocketClient getSocketClient() { return socketClient; }
    public ConfigurationNode getConfig() { return config; }

    public void log(String message) {
        if (config.getNode("debug").getBoolean(false)) {
            logger.info("[调试模式] " + message);
        }
    }
    // 添加用于存储最后一个事件的字段
    private GuildMessageEvent lastEvent;

    // 添加获取服务器名称的方法（从配置中）
    public String getServerName() {
        return getConfig().getNode("server-name").getString("Velocity服务器");
    }

    // 添加设置/获取最后事件的方法
    public void setLastEvent(GuildMessageEvent event) {
        this.lastEvent = event;
    }

    public GuildMessageEvent getLastEvent() {
        return lastEvent;
    }
}