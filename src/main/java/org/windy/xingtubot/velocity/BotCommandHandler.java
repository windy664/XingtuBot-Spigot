package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.core.ai.AiService;
import org.windy.xingtubot.core.api.BotMessageEvent;
import org.windy.xingtubot.core.api.GlobalInstances;
import org.windy.xingtubot.core.api.McmodApiService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Bot 指令处理器，用于解析并响应聊天消息中的命令
 */
public class BotCommandHandler {
    private final ProxyServer proxy;
    private final AiService aiService;
    private final VelocityAdapter adapter;

    public BotCommandHandler(ProxyServer proxy, AiService aiService) {
        this.proxy = proxy;
        this.aiService = aiService;
        this.adapter = new VelocityAdapter(proxy);
    }

    public void handle(BotMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.trim().isEmpty()) return;

        // ==================== AI 聊天 ====================
        if (msg.startsWith("ai ")) {
            String userMsg = msg.substring(3).trim();
            proxy.getConsoleCommandSource().sendMessage(
                    Component.text("[Bot] AI请求: " + userMsg)
            );

            CompletableFuture.runAsync(() -> {
                try {
                    String reply = aiService.chat(userMsg);
                    event.reply(reply);
                } catch (Exception e) {
                    event.reply("AI请求异常: " + e.getMessage());
                }
            });
            return;
        }

        // ==================== 查服 ====================
        if ("/查服".equalsIgnoreCase(msg.trim())) {
            AtomicInteger total = new AtomicInteger(0);
            StringBuilder sb = new StringBuilder();
            sb.append("【服务器在线情况】\n");
            sb.append("-----------------------\n");

            for (RegisteredServer reg : proxy.getAllServers()) {
                String serverName = reg.getServerInfo().getName();

                // 获取该服玩家
                List<Player> playersOnServer = proxy.getAllPlayers().stream()
                        .filter(player -> player.getCurrentServer()
                                .map(serverConn ->
                                        serverConn.getServerInfo().getName().equals(serverName))
                                .orElse(false))
                        .collect(Collectors.toList());

                int count = playersOnServer.size();
                total.addAndGet(count);
                sb.append(serverName).append(" (").append(count).append("人)\n");

                if (!playersOnServer.isEmpty()) {
                    String players = playersOnServer.stream()
                            .map(Player::getUsername)
                            .collect(Collectors.joining(", "));
                    sb.append("  └─ ").append(players).append("\n");
                }
            }

            sb.append("-----------------------\n");
            sb.append("全服总人数: ").append(total.get()).append("人");

            event.reply(sb.toString());
            return;
        }

// ==================== MCMOD 搜索 ====================
        if (msg.toLowerCase().startsWith("/mod ") || msg.matches("\\d+")) {
            // 异步执行防止阻塞
            CompletableFuture.runAsync(() -> {
                // 保留一个全局或单例的 api 对象，而不是每次 new
                McmodApiService api = GlobalInstances.mcmodService;

                api.handleMessage(event);  // ✅ 新方法，包含搜索+选序号逻辑
            });
            return;
        }

        // ==================== 可扩展区 ====================
        // 在这里添加以后新的指令逻辑...
    }
}