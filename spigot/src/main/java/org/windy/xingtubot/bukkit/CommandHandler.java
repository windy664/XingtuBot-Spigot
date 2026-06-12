package org.windy.xingtubot.bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList("reload", "connect", "reply");

    private final XingtuBot plugin;

    public CommandHandler(XingtuBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§c用法: /xtb <reload|connect|reply>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadConfig();
                sender.sendMessage("§a配置已重新加载。");
                return true;

            case "connect":
                if (plugin.getBotBridge() == null) {
                    sender.sendMessage("§c机器人未初始化。");
                    return true;
                }
                try {
                    plugin.getBotBridge().reconnect();
                    sender.sendMessage("§a已尝试重新连接 WebSocket。");
                } catch (Exception e) {
                    sender.sendMessage("§c连接失败: " + e.getMessage());
                }
                return true;

            case "reply":
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /xtb reply <消息>");
                    return true;
                }
                String content = String.join(" ", args).substring(args[0].length() + 1);
                GuildMessageEvent lastEvent = plugin.getLastEvent();
                if (lastEvent == null) {
                    sender.sendMessage("§c当前没有可以回复的事件。");
                    return true;
                }
                lastEvent.reply(content);
                sender.sendMessage("§a已发送回复: " + content);
                return true;

            default:
                sender.sendMessage("§c未知子命令: " + args[0]);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
