package org.windy.xingtubot.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class VCCommandHandler implements SimpleCommand {

    private final VelocityPlugin plugin;
    private final Logger logger;

    public VCCommandHandler(VelocityPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            source.sendMessage(Component.text("用法: /xtb <reload|connect|reply>", TextColor.color(0xFF5555)));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.loadConfig(); // 重新加载配置
                source.sendMessage(Component.text("配置已重新加载", TextColor.color(0x55FF55)));
                break;

            case "connect":
                try {
                    XingtuSocketClient client = plugin.getSocketClient();
                    if (client != null) {
                        client.reconnect();
                        source.sendMessage(Component.text("正在尝试重新连接 WebSocket...", TextColor.color(0x55FF55)));
                    } else {
                        source.sendMessage(Component.text("WebSocket 客户端未初始化", TextColor.color(0xFF5555)));
                    }
                } catch (Exception e) {
                    source.sendMessage(Component.text("连接失败: " + e.getMessage(), TextColor.color(0xFF5555)));
                }
                break;

            case "reply":
                if (args.length < 2) {
                    source.sendMessage(Component.text("用法: /xtb reply <消息>", TextColor.color(0xFF5555)));
                    return;
                }

                GuildMessageEvent lastEvent = plugin.getLastEvent();
                if (lastEvent == null) {
                    source.sendMessage(Component.text("当前没有可回复的事件", TextColor.color(0xFF5555)));
                    return;
                }

                String content = String.join(" ", args).substring(args[0].length() + 1);
                lastEvent.reply(content);
                source.sendMessage(Component.text("已发送回复: " + content, TextColor.color(0x55FF55)));
                break;

            default:
                source.sendMessage(Component.text("未知子命令: " + args[0], TextColor.color(0xFF5555)));
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> suggestions = new ArrayList<>();

        if (args.length == 0 || args.length == 1) {
            List<String> subCommands = List.of("reload", "connect", "reply");
            String currentArg = args.length == 1 ? args[0].toLowerCase() : "";

            return CompletableFuture.completedFuture(
                    subCommands.stream()
                            .filter(s -> s.startsWith(currentArg))
                            .collect(Collectors.toList())
            );
        }
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}