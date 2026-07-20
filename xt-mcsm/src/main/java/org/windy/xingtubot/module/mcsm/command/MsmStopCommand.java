package org.windy.xingtubot.module.mcsm.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;

import java.util.List;

/**
 * msm 停止 <实例名> — 停止实例。
 */
public class MsmStopCommand implements BotCommand {

    private final McsmClient client;
    private final InstanceResolver resolver;
    private final Runnable markManualOp;

    public MsmStopCommand(McsmClient client, InstanceResolver resolver, Runnable markManualOp) {
        this.client = client;
        this.resolver = resolver;
        this.markManualOp = markManualOp;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.startsWith("msm 停止 ");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String name = message.trim().substring("msm 停止 ".length()).trim();
        if (name.isEmpty()) {
            event.reply("用法: msm 停止 <实例名>");
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
            client.stopInstance(ref.uuid, ref.daemonId);
            event.reply("✅ 已发送停止指令: **" + ref.displayName + "**");
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 停止失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-stop"; }
    @Override public boolean adminOnly() { return true; }
    @Override public String usage() { return "msm 停止 <实例名>"; }
    @Override public String description() { return "停止实例"; }
    @Override public String category() { return "🖥️ 服务器管理"; }
}
