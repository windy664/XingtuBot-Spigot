package org.windy.xingtubot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.binding.BindingStorageFactory;
import org.windy.xingtubot.common.bot.BotCore;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.poll.QqWebhookBot;
import org.windy.xingtubot.common.service.McmodApiService;
import org.windy.xingtubot.common.ws.WebSocketClientBridge;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Plugin(
        id = "xingtubotvelocity",
        name = "XingtuBotVelocity",
        version = "1.3",
        authors = {"风吟"}
)
public class XingtuBotVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private BotCore botCore;
    // 仅依赖接口，避免把 java-websocket 暴露到 velocity 编译期
    private WebSocketClientBridge wsClient;
    private QqWebhookBot webhookBot;
    private VelocityAdapter adapter;
    private BotCommandHandler commandHandler;
    private final ScheduledExecutorService monitorScheduler =
            Executors.newSingleThreadScheduledExecutor();

    @Inject
    public XingtuBotVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        // 不在构造函数里注册事件/通道：此时插件容器尚未就绪，必须等 ProxyInitializeEvent。
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        init();
    }

    private void init() {
        VelocityConfig config = new VelocityConfig(dataDir);
        String apiKey = config.getString("deepseek-api-key", "");
        String baseUrl = config.getString("deepseek-base-url", "https://api.deepseek.com");

        VelocityBotLogger botLogger = new VelocityBotLogger(logger);
        AiService aiService = new AiService(apiKey, baseUrl);
        McmodApiService mcmod = new McmodApiService(botLogger);
        // mcmod 详情卡片化（Markdown）开关，需机器人有原生 markdown 权限
        mcmod.setMarkdownEnabled(config.getBoolean("mcmod-markdown", false));

        adapter = new VelocityAdapter(proxy);

        // 白名单「大脑」：Velocity 收群消息 + 头像比对 + pending，AuthMe 执行下发给子服。
        // 仅当 Velocity 自己会收群消息（bot-mode 非 off）时才当大脑，否则建了也收不到消息。
        BindingService bindingService = null;
        boolean velocityIsBrain = config.getBoolean("whitelist-enable", true)
                && BotLauncher.resolveMode(config) != BotLauncher.Mode.OFF;
        if (velocityIsBrain) {
            String appId = config.getString("openapi-app-id", "");
            if (appId.isEmpty()) {
                logger.warn("[XingtuBot] 未配置 openapi-app-id，白名单头像比对将无法工作");
            }
            ChannelIdentifier bridgeChannel = MinecraftChannelIdentifier.from(CrossServerProtocol.CHANNEL);
            PluginMessageAuthAdapter authAdapter = new PluginMessageAuthAdapter(proxy, bridgeChannel);
            // 大脑端：按 storage-type 建仓库（json/sqlite 文件落数据目录，mysql 直连）
            BindingRepository store = BindingStorageFactory.create(
                    config, true, dataDir.toFile(), logger::warn);
            bindingService = new BindingService(store, authAdapter, appId, logger::warn);
            new VelocityBridge(proxy, this, bridgeChannel, bindingService, authAdapter, logger::warn);
            adapter.log("✅ 白名单大脑已就绪（Velocity 主导，子服执行 AuthMe）");
        }

        commandHandler = new BotCommandHandler(proxy, aiService, mcmod, config, bindingService);
        proxy.getCommandManager().register("vxtb", new VxtbCommand());

        // 收到消息后的统一处理（两种模式共用）
        Consumer<BotMessageEvent> listener = event -> {
            adapter.log("[VC] 收到Bot消息: " + event.getMessage());
            commandHandler.handle(event);
        };

        switch (BotLauncher.resolveMode(config)) {
            case OFF:
                adapter.log("通信模式 = off，机器人通信未启用。");
                return;
            case WEBHOOK:
                webhookBot = BotLauncher.buildWebhook(config, adapter, listener);
                if (webhookBot != null) {
                    webhookBot.start();
                    adapter.log("✅ 通信模式 = webhook（SCF 长轮询 + OpenAPI 回复）已启动");
                } else {
                    logger.error("[XingtuBot] Webhook 模式配置不全，需填写 webhook-relay-url / openapi-app-id / openapi-client-secret，未启动。");
                }
                return;
            case WEBSOCKET:
            default:
                startWebSocket(config, listener);
        }
    }

    /** WebSocket 模式：老星途框架长连接。 */
    private void startWebSocket(VelocityConfig config, Consumer<BotMessageEvent> listener) {
        String wsUrl = config.getString("WebSocket", "ws://127.0.0.1:8080");
        String serverName = config.getString("server-name", "velocity");
        long heartbeatMillis = config.getInt("HeartbeatSeconds", 30) * 1000L;
        try {
            wsClient = WebSocketClientBridge.create(wsUrl);
            wsClient.enableHeartbeat(heartbeatMillis);

            botCore = new BotCore(adapter, wsClient, serverName);
            botCore.addMessageListener(listener);

            botCore.start();
            adapter.log("✅ 通信模式 = websocket，正在连接 " + wsUrl);
            startConnectionMonitor();
        } catch (Exception e) {
            logger.error("[XingtuBot] WebSocket启动失败: {}", e.getMessage());
        }
    }

    /** 看门狗：每 60 秒检查连接状态 */
    private void startConnectionMonitor() {
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                if (wsClient == null) return;
                if (!wsClient.isOpen()) {
                    adapter.log("🔁 [Watchdog] WebSocket 已断开，尝试重连...");
                    wsClient.reconnect();
                } else {
                    adapter.log("💓 [Watchdog] WebSocket 连接正常");
                }
            } catch (Exception e) {
                adapter.log("⚠️ [Watchdog异常] " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // ==================== /vxtb 命令 ====================

    private class VxtbCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            if (args.length == 0) {
                sender.sendMessage(Component.text("用法: /vxtb <connect|disconnect|status|captureid>"));
                return;
            }

            switch (args[0].toLowerCase()) {
                case "connect":
                    handleConnect(sender);
                    break;
                case "disconnect":
                    handleDisconnect(sender);
                    break;
                case "status":
                    handleStatus(sender);
                    break;
                case "captureid":
                    if (commandHandler == null) {
                        sender.sendMessage(Component.text("❌ 机器人未就绪"));
                    } else {
                        commandHandler.startCaptureOpenid();
                        sender.sendMessage(Component.text(
                                "✅ 已开启 openid 捕获。请让目标用户在群里 @机器人 发任意一句话，"
                                + "其 openid 将打印到本控制台（一次性）。"));
                    }
                    break;
                default:
                    sender.sendMessage(Component.text("未知子命令: " + args[0]));
            }
        }

        private void handleConnect(CommandSource sender) {
            if (wsClient == null) {
                sender.sendMessage(Component.text("❌ WebSocket 尚未初始化。"));
                return;
            }
            if (wsClient.isOpen()) {
                sender.sendMessage(Component.text("✅ WebSocket 当前已连接。"));
                return;
            }
            adapter.log("🔌 手动执行连接 WebSocket...");
            wsClient.connect();
            sender.sendMessage(Component.text("正在尝试连接 WebSocket..."));
        }

        private void handleDisconnect(CommandSource sender) {
            if (wsClient == null) {
                sender.sendMessage(Component.text("❌ WebSocket 尚未初始化。"));
                return;
            }
            wsClient.disconnect();
            sender.sendMessage(Component.text("🧹 已手动断开 WebSocket 连接。"));
        }

        private void handleStatus(CommandSource sender) {
            if (wsClient == null) {
                sender.sendMessage(Component.text("❌ WebSocket 未初始化。"));
                return;
            }
            String status = wsClient.isOpen() ? "✅ 已连接" : "❌ 未连接";
            sender.sendMessage(Component.text("WebSocket 状态: " + status +
                    (wsClient.isOpen() ? " | 心跳间隔: 30s" : "")));
        }
    }
}
