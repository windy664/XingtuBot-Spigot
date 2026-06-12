package org.windy.xingtubot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.bot.BotCore;
import org.windy.xingtubot.common.service.McmodApiService;
import org.windy.xingtubot.common.ws.WebSocketClientBridge;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private VelocityAdapter adapter;
    private final ScheduledExecutorService monitorScheduler =
            Executors.newSingleThreadScheduledExecutor();

    @Inject
    public XingtuBotVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        init();
    }

    private void init() {
        VelocityConfig config = new VelocityConfig(dataDir);
        String apiKey = config.getString("deepseek-api-key", "");
        String baseUrl = config.getString("deepseek-base-url", "https://api.deepseek.com");
        String wsUrl = config.getString("WebSocket", "ws://127.0.0.1:8080");
        String serverName = config.getString("server-name", "velocity");
        long heartbeatMillis = config.getInt("HeartbeatSeconds", 30) * 1000L;

        VelocityBotLogger botLogger = new VelocityBotLogger(logger);
        AiService aiService = new AiService(apiKey, baseUrl);
        McmodApiService mcmod = new McmodApiService(botLogger);

        adapter = new VelocityAdapter(proxy);
        BotCommandHandler commandHandler = new BotCommandHandler(proxy, aiService, mcmod);

        try {
            wsClient = WebSocketClientBridge.create(wsUrl);
            wsClient.enableHeartbeat(heartbeatMillis);

            botCore = new BotCore(adapter, wsClient, serverName);
            botCore.addMessageListener(event -> {
                adapter.log("[VC] 收到Bot消息: " + event.getMessage());
                commandHandler.handle(event);
            });

            botCore.start();
            adapter.log("✅ XingtuBotVelocity 已启动，WebSocket正在连接...");

            startConnectionMonitor();
            proxy.getCommandManager().register("vxtb", new VxtbCommand());
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
                sender.sendMessage(Component.text("用法: /vxtb <connect|disconnect|status>"));
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
