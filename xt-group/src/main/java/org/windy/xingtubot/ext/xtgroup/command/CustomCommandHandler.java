package org.windy.xingtubot.ext.xtgroup.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.MenuEntry;
import org.windy.xingtubot.common.util.ColorCodeConverter;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 自定义命令处理器。
 * 从 commands.yml 加载命令定义，支持：
 * - 玩家/控制台身份执行
 * - %player_name% 占位符（查绑定库）
 * - 跨服执行（Velocity 模式）
 * - 未绑定提示
 */
public class CustomCommandHandler implements BotCommand {

    private final CustomCommandConfig config;
    private final Function<String, String> playerLookup; // openid → 玩家名，null 表示不支持
    private final CommandExecutor consoleExecutor;
    private final CommandExecutor playerExecutor;
    private final CrossServerExecutor crossServerExecutor;
    private PlaceholderResolver placeholderResolver;

    @FunctionalInterface
    public interface CommandExecutor {
        void execute(String target, String command, Consumer<String> callback);
    }

    @FunctionalInterface
    public interface CrossServerExecutor {
        void execute(String server, String command, Consumer<String> callback);
    }

    public CustomCommandHandler(CustomCommandConfig config,
                                Function<String, String> playerLookup,
                                CommandExecutor consoleExecutor,
                                CommandExecutor playerExecutor,
                                CrossServerExecutor crossServerExecutor) {
        this.config = config;
        this.playerLookup = playerLookup;
        this.consoleExecutor = consoleExecutor;
        this.playerExecutor = playerExecutor;
        this.crossServerExecutor = crossServerExecutor;
    }

    public void setPlaceholderResolver(PlaceholderResolver resolver) {
        this.placeholderResolver = resolver;
    }

    @Override
    public boolean matches(String message) {
        return config.match(message.trim()) != null;
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        CustomCommandConfig.Entry entry = config.match(message.trim());
        if (entry == null) return;

        String args = config.extractArgs(message, entry);

        // 需要绑定 → 查绑定库
        String playerName = null;
        if (entry.needBind) {
            playerName = resolvePlayerName(event.getSenderId());
            if (playerName == null) {
                event.reply(entry.notBoundMsg);
                return;
            }
        }

        // 替换占位符
        String resolvedCmd = entry.command;
        if (playerName != null) {
            resolvedCmd = resolvedCmd.replace("%player_name%", playerName);
        }
        resolvedCmd = resolvedCmd.replace("{args}", args);

        final String finalCmd = resolvedCmd;
        final String finalPlayer = playerName;

        if (placeholderResolver != null) {
            placeholderResolver.resolve(finalCmd, event, cmd ->
                    executeCommand(cmd, entry, finalPlayer, event));
        } else {
            executeCommand(finalCmd, entry, finalPlayer, event);
        }
    }

    private void executeCommand(String resolvedCmd, CustomCommandConfig.Entry entry, String playerName, BotMessageContext event) {
        if (entry.execAs == CustomCommandConfig.ExecAs.PLAYER) {
            if (playerName == null) {
                playerName = resolvePlayerName(event.getSenderId());
                if (playerName == null) {
                    event.reply("需要绑定白名单才能以玩家身份执行此命令");
                    return;
                }
                resolvedCmd = resolvedCmd.replace("%player_name%", playerName);
            }
            if (playerExecutor != null) {
                final String finalPlayer = playerName;
                playerExecutor.execute(finalPlayer, resolvedCmd, output -> replySmart(event, output));
            } else {
                event.reply("当前模式不支持以玩家身份执行命令");
            }
        } else {
            if (crossServerExecutor != null) {
                String target = entry.server.isEmpty() ? "all" : entry.server;
                crossServerExecutor.execute(target, resolvedCmd, output -> replySmart(event, output));
            } else if (consoleExecutor != null) {
                consoleExecutor.execute(null, resolvedCmd, output -> replySmart(event, output));
            } else {
                event.reply("当前模式不支持执行命令");
            }
        }
    }

    @Override
    public java.util.List<String> triggers() {
        java.util.List<String> triggers = new java.util.ArrayList<>();
        for (CustomCommandConfig.Entry e : config.getEntries()) {
            if (e.trigger != null && !e.trigger.isEmpty()) {
                triggers.add(e.trigger);
            }
        }
        return triggers;
    }

    @Override
    public String name() { return "custom-command"; }

    @Override
    public boolean adminOnly() { return false; }

    @Override
    public boolean adminFor(String message) {
        CustomCommandConfig.Entry entry = config.match(message.trim());
        return entry != null && entry.admin;
    }

    @Override
    public String usage() { return null; }

    @Override
    public String description() { return ""; }

    // category 自动继承模块 displayName（"💬 群功能"）

    @Override
    public List<MenuEntry> menuEntries() {
        List<MenuEntry> out = new ArrayList<>();
        for (CustomCommandConfig.Entry e : config.getEntries()) {
            if (e.trigger == null || e.trigger.isEmpty()) continue;
            String label = (e.description != null && !e.description.isEmpty())
                    ? e.description : e.trigger;
            out.add(new MenuEntry(e.trigger, label, "", e.admin));
        }
        return out;
    }

    private String resolvePlayerName(String openid) {
        if (playerLookup == null || openid == null) return null;
        return playerLookup.apply(openid);
    }

    private void replySmart(BotMessageContext event, String output) {
        if (output == null || output.isEmpty()) return;
        if (output.contains("§")) {
            event.replyMarkdown(
                    ColorCodeConverter.toMarkdown(output), null);
        } else {
            event.reply(output);
        }
    }
}
