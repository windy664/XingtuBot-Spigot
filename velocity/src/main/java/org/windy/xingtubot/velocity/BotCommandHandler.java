package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.service.McmodApiService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Bot 指令处理器：解析群消息中的命令（AI / 查服 / MCMOD 搜索）。
 */
public class BotCommandHandler {
    private final ProxyServer proxy;
    private final AiService aiService;
    private final McmodApiService mcmodService;

    public BotCommandHandler(ProxyServer proxy, AiService aiService, McmodApiService mcmodService) {
        this.proxy = proxy;
        this.aiService = aiService;
        this.mcmodService = mcmodService;
    }

    public void handle(BotMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.trim().isEmpty()) return;

        // ==================== AI 聊天 ====================
        if (msg.startsWith("ai ")) {
            String userMsg = msg.substring(3).trim();
            proxy.getConsoleCommandSource().sendMessage(Component.text("[Bot] AI请求: " + userMsg));
            CompletableFuture.runAsync(() -> {
                try {
                    event.reply(aiService.chat(userMsg));
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
                List<Player> playersOnServer = proxy.getAllPlayers().stream()
                        .filter(player -> player.getCurrentServer()
                                .map(conn -> conn.getServerInfo().getName().equals(serverName))
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
            CompletableFuture.runAsync(() -> mcmodService.handleMessage(event));
        }
    }
}
