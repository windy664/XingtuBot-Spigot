package org.windy.xingtubot.common.command.impl;

import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.command.CustomCommandConfig;
import org.windy.xingtubot.common.command.CustomCommandConfig.Entry;
import org.windy.xingtubot.common.command.GroupCommand;
import org.windy.xingtubot.common.event.BotMessageEvent;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 自定义命令处理器。
 * 从 commands.yml 加载命令定义，支持：
 * - 玩家/控制台身份执行
 * - %player_name% 占位符（查绑定库）
 * - 跨服执行（Velocity 模式）
 * - 未绑定提示
 */
public class CustomCommandHandler implements GroupCommand {

    private final CustomCommandConfig config;
    private final BindingRepository bindingStore;

    /**
     * 控制台命令执行器：(target, command, callback) -> 执行命令并返回输出。
     * 平台侧注入具体实现。
     */
    private final CommandExecutor consoleExecutor;

    /**
     * 玩家命令执行器：(playerName, command, callback) -> 以玩家身份执行，输出回调。
     * 平台侧注入。null 表示不支持。
     */
    private final CommandExecutor playerExecutor;

    /**
     * 跨服执行器：(server, command, callback) -> 跨服执行。
     * 仅 Velocity 端注入。null 表示不支持（忽略 server 字段）。
     */
    private final CrossServerExecutor crossServerExecutor;

    /** 占位符解析器（PAPI + 内置）。null 表示不解析。 */
    private org.windy.xingtubot.common.reply.PlaceholderResolver placeholderResolver;

    @FunctionalInterface
    public interface CommandExecutor {
        void execute(String target, String command, java.util.function.Consumer<String> callback);
    }

    @FunctionalInterface
    public interface CrossServerExecutor {
        void execute(String server, String command, java.util.function.Consumer<String> callback);
    }

    public CustomCommandHandler(CustomCommandConfig config, BindingRepository bindingStore,
                                CommandExecutor consoleExecutor,
                                CommandExecutor playerExecutor,
                                CrossServerExecutor crossServerExecutor) {
        this.config = config;
        this.bindingStore = bindingStore;
        this.consoleExecutor = consoleExecutor;
        this.playerExecutor = playerExecutor;
        this.crossServerExecutor = crossServerExecutor;
    }

    /** 设置占位符解析器（PAPI + 内置占位符）。 */
    public void setPlaceholderResolver(org.windy.xingtubot.common.reply.PlaceholderResolver resolver) {
        this.placeholderResolver = resolver;
    }

    @Override
    public boolean matches(String message) {
        return config.match(message.trim()) != null;
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        Entry entry = config.match(message.trim());
        if (entry == null) return;

        // 权限检查（adminOnly 已在 GroupCommandRegistry/HandlerRegistry 中处理）

        String args = config.extractArgs(message, entry);

        // 需要绑定 → 查绑定库
        String playerName = null;
        if (entry.needBind) {
            playerName = resolvePlayerName(event.getFormId());
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

        // PAPI + 内置占位符解析
        final String finalCmd = resolvedCmd;
        final String finalPlayer = playerName;

        // 异步解析占位符（Spigot 本地同步回调，Velocity PAPI 跨服异步）
        if (placeholderResolver != null) {
            placeholderResolver.resolve(finalCmd, event, cmd -> {
                executeCommand(cmd, entry, finalPlayer, event);
            });
        } else {
            executeCommand(finalCmd, entry, finalPlayer, event);
        }
    }

    /** 执行命令（占位符解析完成后调用）。 */
    private void executeCommand(String resolvedCmd, Entry entry, String playerName, BotMessageEvent event) {
        if (entry.execAs == CustomCommandConfig.ExecAs.PLAYER) {
            if (playerName == null) {
                playerName = resolvePlayerName(event.getFormId());
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

    /** 所有自定义命令的 trigger（供 AI 模块排除已注册前缀）。 */
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
    public String name() {
        return "custom-command";
    }

    @Override
    public boolean adminOnly() {
        // 自定义命令的 admin 标记在 Entry 上，这里返回 false；按消息粒度走 adminFor。
        return false;
    }

    /**
     * 自定义命令的管理员判定（按命中条目的 admin 标记）。
     * 由 HandlerRegistry 在分发时经 adminFor 调用。
     */
    @Override
    public boolean adminFor(String message) {
        Entry entry = config.match(message.trim());
        return entry != null && entry.admin;
    }

    @Override
    public String usage() {
        return null; // 不进菜单（自定义命令的菜单由 replies.yml 的 menu 条目控制）
    }

    @Override
    public String description() {
        return "";
    }

    /** 查询 openid 绑定的玩家名。未绑定返回 null。 */
    private String resolvePlayerName(String openid) {
        if (bindingStore == null || openid == null) return null;
        org.windy.xingtubot.common.binding.BindingEntry e = bindingStore.findByOpenid(openid);
        return e != null ? e.player : null;
    }

    /** 智能回复：命令输出有 MC 颜色码时转 Markdown，否则纯文本。 */
    private void replySmart(BotMessageEvent event, String output) {
        if (output == null || output.isEmpty()) return;
        if (output.contains("§")) {
            event.replyMarkdown(
                    org.windy.xingtubot.common.util.ColorCodeConverter.toMarkdown(output), null);
        } else {
            event.reply(output);
        }
    }
}
