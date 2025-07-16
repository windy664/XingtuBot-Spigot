package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.windy.xingtubot.core.ai.AiService;
import org.windy.xingtubot.core.api.BotMessageEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class BotCommandHandler {
    private final ProxyServer proxy;

    private final AiService aiService;

    public BotCommandHandler(ProxyServer proxy, AiService aiService) {
        this.proxy = proxy;
        this.aiService = aiService;
    }

    public void handle(BotMessageEvent event) {
        String msg = event.getMessage();

        if (msg.startsWith("ai ")) {
            String userMsg = msg.substring(3).trim();
            proxy.getConsoleCommandSource().sendMessage(
                    net.kyori.adventure.text.Component.text("[Bot] AI请求: " + userMsg)
            );
            // 异步调用AI
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

        if ("/查服".equals(msg)) {
            AtomicInteger total = new AtomicInteger(0);
            StringBuilder sb = new StringBuilder();
            sb.append("【服务器在线情况】\n");
            sb.append("-----------------------\n");

            for (RegisteredServer reg : proxy.getAllServers()) {
                String serverName = reg.getServerInfo().getName();

                // 取该服所有玩家
                List<Player> playersOnServer = proxy.getAllPlayers().stream()
                        .filter(player -> player.getCurrentServer()
                                .map(serverConn -> serverConn.getServerInfo().getName().equals(serverName))
                                .orElse(false))
                        .collect(Collectors.toList());

                int count = playersOnServer.size();
                sb.append(serverName)
                        .append(" (").append(count).append("人)\n");

                if (!playersOnServer.isEmpty()) {
                    String players = playersOnServer.stream()
                            .map(Player::getUsername)
                            .collect(Collectors.joining(", "));
                    sb.append("  └─ ").append(players).append("\n");
                }
            }

            sb.append("-----------------------\n");
            int totalCount = (int) proxy.getAllPlayers().stream().count();
            sb.append("全服总人数: ").append(totalCount).append("人");

            event.reply(sb.toString());
            return;
        }
    }
}