package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.module.capability.ConsoleExecutor;
import org.windy.xingtubot.common.module.capability.CrossServerConsole;
import org.windy.xingtubot.common.util.ColorCodeConverter;

import java.util.Arrays;
import java.util.List;

/**
 * 超管远程执行命令。
 * <ul>
 *   <li>{@code 执行 say Hello} → 本服控制台执行</li>
 *   <li>{@code 执行 @lobby say Hello} → 指定子服执行</li>
 * </ul>
 * 仅管理员可用。依赖平台注册的 {@link ConsoleExecutor} / {@link CrossServerConsole} 能力。
 */
public class ExecCommand implements BotCommand {

    private final ConsoleExecutor console;
    private final CrossServerConsole crossServer;

    public ExecCommand(ConsoleExecutor console, CrossServerConsole crossServer) {
        this.console = console;
        this.crossServer = crossServer;
    }

    @Override
    public boolean matches(String message) {
        return message != null && message.trim().startsWith("执行 ");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String args = message.trim().substring(3).trim();
        if (args.isEmpty()) {
            event.reply("用法: 执行 <命令> / 执行 @子服 <命令>");
            return;
        }

        // 跨服：执行 @server command
        if (args.startsWith("@")) {
            int space = args.indexOf(' ');
            if (space > 1 && crossServer != null) {
                String server = args.substring(1, space);
                String cmd = args.substring(space + 1).trim();
                if (!cmd.isEmpty()) {
                    crossServer.exec(server, cmd, output -> replyOutput(event, cmd, output));
                    return;
                }
            }
            event.reply("跨服用法: 执行 @子服名 <命令>");
            return;
        }

        // 本服
        if (console == null) {
            event.reply("当前模式不支持远程执行命令");
            return;
        }
        console.exec(args, output -> replyOutput(event, args, output));
    }

    private void replyOutput(BotMessageContext event, String cmd, String output) {
        if (output == null || output.isEmpty()) {
            event.reply("✅ 已执行: " + cmd);
        } else if (output.contains("§")) {
            event.replyMarkdown(ColorCodeConverter.toMarkdown(output), null);
        } else {
            event.reply(output);
        }
    }

    @Override
    public String name() { return "exec"; }

    @Override
    public boolean adminOnly() { return true; }

    @Override
    public List<String> triggers() { return Arrays.asList("执行"); }

    @Override
    public String usage() { return "执行 <命令>"; }

    @Override
    public String description() { return "远程执行服务器命令"; }

    @Override
    public String category() { return "🔧 管理"; }
}
