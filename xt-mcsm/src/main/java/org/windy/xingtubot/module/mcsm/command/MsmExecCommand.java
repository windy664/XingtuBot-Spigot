package org.windy.xingtubot.module.mcsm.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;

import java.util.List;

/**
 * msm 命令 <实例名> <命令> — 向实例发送控制台命令。
 */
public class MsmExecCommand implements BotCommand {

    private final McsmClient client;
    private final InstanceResolver resolver;

    public MsmExecCommand(McsmClient client, InstanceResolver resolver) {
        this.client = client;
        this.resolver = resolver;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.startsWith("msm 命令 ");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String args = message.trim().substring("msm 命令 ".length()).trim();
        if (args.isEmpty()) {
            event.reply("用法: msm 命令 <实例名> <命令>");
            return;
        }

        // 分割：第一个空格前是实例名，后面是命令
        int space = args.indexOf(' ');
        if (space < 0) {
            event.reply("用法: msm 命令 <实例名> <命令>");
            return;
        }
        String name = args.substring(0, space).trim();
        String command = args.substring(space + 1).trim();
        if (command.isEmpty()) {
            event.reply("用法: msm 命令 <实例名> <命令>");
            return;
        }

        InstanceResolver.Ref ref = resolver.resolve(name);
        if (ref == null) {
            List<String> suggestions = resolver.suggest(name);
            String hint = suggestions.isEmpty() ? "" : "\n> 你是不是想说: " + String.join(", ", suggestions);
            event.reply("❌ 未找到实例: " + name + hint);
            return;
        }

        try {
            client.sendCommand(ref.uuid, ref.daemonId, command);
            event.reply("✅ 已向 **" + ref.displayName + "** 发送命令: `" + command + "`");
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 发送命令失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-exec"; }
    @Override public boolean adminOnly() { return true; }
    @Override public String usage() { return "msm 命令 <实例名> <命令>"; }
    @Override public String description() { return "向实例发送控制台命令"; }
    // category 自动继承模块 displayName
}
