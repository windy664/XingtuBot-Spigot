package org.windy.xingtubot.module.mcsm.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;

import java.util.List;

/**
 * msm 强杀 <实例名> — 强制结束实例进程。
 */
public class MsmKillCommand implements BotCommand {

    private final McsmClient client;
    private final InstanceResolver resolver;
    private final Runnable markManualOp;

    public MsmKillCommand(McsmClient client, InstanceResolver resolver, Runnable markManualOp) {
        this.client = client;
        this.resolver = resolver;
        this.markManualOp = markManualOp;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.startsWith("msm 强杀 ");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String name = message.trim().substring("msm 强杀 ".length()).trim();
        if (name.isEmpty()) {
            event.reply("用法: msm 强杀 <实例名>");
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
            markManualOp.run();
            client.killInstance(ref.uuid, ref.daemonId);
            event.reply("⚡ 已发送强杀指令: **" + ref.displayName + "**");
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 强杀失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-kill"; }
    @Override public boolean adminOnly() { return true; }
    @Override public String usage() { return "msm 强杀 <实例名>"; }
    @Override public String description() { return "强制结束实例进程"; }
    // category 自动继承模块 displayName
}
