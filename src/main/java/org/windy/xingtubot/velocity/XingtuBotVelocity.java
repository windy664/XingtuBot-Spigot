package org.windy.xingtubot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.core.ai.AiService;
import org.windy.xingtubot.core.api.BotCore;
import org.windy.xingtubot.core.api.McmodApiService;
import org.windy.xingtubot.core.ws.StandardWebSocketClient;

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
    private BotCore botCore;
    private StandardWebSocketClient wsClient;
    private final ScheduledExecutorService monitorScheduler =
            Executors.newSingleThreadScheduledExecutor();
    private VelocityAdapter adapter;

    @Inject
    public XingtuBotVelocity(ProxyServer proxy) {
        this.proxy = proxy;
        init();
    }

    /** 初始化插件 **/
    private void init() {
        String apiKey = "sk-****************";
        String baseUrl = "https://api.deepseek.com/v1";
        AiService aiService = new AiService(apiKey, baseUrl);

        BotCommandHandler commandHandler = new BotCommandHandler(proxy, aiService);
        adapter = new VelocityAdapter(proxy);

        try {
            String wsUrl = "ws://127.0.0.1:8080"; // 可迁移到配置
            wsClient = new StandardWebSocketClient(wsUrl);
            wsClient.enableHeartbeat(30_000);

            botCore = new BotCore(adapter, wsClient);
            botCore.addMessageListener(event -> {
                adapter.log("[VC] 收到Bot消息: " + event.getMessage());
                commandHandler.handle(event);
            });

            botCore.start();
            adapter.log("✅ XingtuBotVelocity 已启动，WebSocket正在连接...");

            startConnectionMonitor(adapter);

            // 注册命令
            proxy.getCommandManager().register("vxtb", new VxtbCommand());

        } catch (Exception e) {
            proxy.getConsoleCommandSource().sendMessage(
                    Component.text("[XingtuBot] ❌ WebSocket启动失败: " + e.getMessage())
            );
        }
        McmodApiService.setProxy(null,0,null);
        //McmodApiService.setProxy("127.0.0.1", 7890, "http");
    }

    /** 看门狗：每 60 秒检查连接状态 **/
    private void startConnectionMonitor(VelocityAdapter adapter) {
        monitorScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
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
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // ==================== /vxtb 命令实现（Java 8 写法） ====================

    private class VxtbCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            if (args.length == 0) {
                sender.sendMessage(Component.text("用法: /vxtb <connect|disconnect|status>"));
                return;
            }

            String sub = args[0].toLowerCase();
            if ("connect".equals(sub)) {
                handleConnect(sender);
            } else if ("disconnect".equals(sub)) {
                handleDisconnect(sender);
            } else if ("status".equals(sub)) {
                handleStatus(sender);
            } else {
                sender.sendMessage(Component.text("未知子命令: " + sub));
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