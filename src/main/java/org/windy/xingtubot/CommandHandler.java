package org.windy.xingtubot;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.windy.xingtubot.event.GuildMessageEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class CommandHandler implements CommandExecutor, TabCompleter {

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
                try {
                    plugin.getSocketClient().reconnect();
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
            List<String> subCommands = new ArrayList<>();
            subCommands.add("reload");
            subCommands.add("connect");
            subCommands.add("reply");
            return subCommands.stream()
                    .filter(s -> s.startsWith(args[0]))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

}