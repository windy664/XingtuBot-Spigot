package org.windy.xingtubot.module.mcsm.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;

import java.util.Collections;
import java.util.List;

/**
 * msm 启动 <实例名> — 启动实例。
 */
public class MsmStartCommand implements BotCommand {

    private final McsmClient client;
    private final InstanceResolver resolver;
    private final Runnable markManualOp;

    public MsmStartCommand(McsmClient client, InstanceResolver resolver, Runnable markManualOp) {
        this.client = client;
        this.resolver = resolver;
        this.markManualOp = markManualOp;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.startsWith("msm 启动 ");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String name = message.trim().substring("msm 启动 ".length()).trim();
        if (name.isEmpty()) {
            event.reply("用法: msm 启动 <实例名>");
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
            client.startInstance(ref.uuid, ref.daemonId);
            event.reply("✅ 已发送启动指令: **" + ref.displayName + "**");
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 启动失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-start"; }
    @Override public boolean adminOnly() { return true; }
    @Override public String usage() { return "msm 启动 <实例名>"; }
    @Override public String description() { return "启动实例"; }
    @Override public String category() { return "🖥️ 服务器管理"; }
}
