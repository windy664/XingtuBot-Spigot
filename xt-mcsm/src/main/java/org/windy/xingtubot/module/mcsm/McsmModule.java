package org.windy.xingtubot.module.mcsm;

import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.module.mcsm.command.*;

/**
 * MCSM 面板管理模块。
 */
public final class McsmModule implements BotModule {

    private McsmMonitor monitor;

    @Override
    public String name() {
        return "mcsm";
    }

    @Override
    public String displayName() {
        return "🖥️ 服务器管理";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        McsmConfig config = new McsmConfig(ctx.config());

        if (config.panelUrl().isEmpty()) {
            ctx.logger().warn("[MCSM] 未配置 panel-url，模块未加载");
            return;
        }

        McsmClient client = new McsmClient(config.panelUrl(), config.apiKey());
        InstanceResolver resolver = new InstanceResolver(client, config);

        // 手动操作标记：用于抑制崩溃告警
        Runnable markManualOp = () -> {
            if (monitor != null) monitor.markManualOperation();
        };

        // 注册命令
        ctx.registry().register(new MsmStatusCommand(client, config.sanitize()));
        ctx.registry().register(new MsmNodeCommand(client, config.sanitize()));
        ctx.registry().register(new MsmListCommand(client, config.sanitize()));
        ctx.registry().register(new MsmDetailCommand(client, config.sanitize(), resolver));
        ctx.registry().register(new MsmStartCommand(client, resolver, markManualOp));
        ctx.registry().register(new MsmStopCommand(client, resolver, markManualOp));
        ctx.registry().register(new MsmRestartCommand(client, resolver, markManualOp));
        ctx.registry().register(new MsmKillCommand(client, resolver, markManualOp));
        ctx.registry().register(new MsmExecCommand(client, resolver));
        ctx.registry().register(new MsmBatchCommand(client, markManualOp));

        // 启动崩溃监控
        if (config.pollInterval() > 0) {
            ProactiveSender sender = ctx.getService(ProactiveSender.class);
            monitor = new McsmMonitor(client, config, sender, ctx.logger());
            monitor.start();
            ctx.logger().info("[MCSM] 崩溃监控已启动 (间隔 " + config.pollInterval() + "s)");
        }

        ctx.logger().info("[MCSM] 面板管理模块已加载 → " + config.panelUrl());
    }

    @Override
    public void onDisable() {
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
    }
}
