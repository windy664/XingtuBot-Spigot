package org.windy.xingtubot.springboot;

import org.springframework.context.ApplicationContext;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.handler.AbstractCommandHandler;
import org.windy.xingtubot.common.module.ModuleContextImpl;
import org.windy.xingtubot.common.module.capability.ConsoleExecutor;
import org.windy.xingtubot.common.module.capability.CrossServerConsole;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.io.File;

/**
 * Spring Boot 端命令中心。
 *
 * <p>平台差异：
 * <ul>
 *   <li>{@link ConsoleExecutor}：无游戏控制台，退化为日志输出</li>
 *   <li>{@link PlaceholderResolver}：无 PAPI，返回简单实现</li>
 *   <li>平台对象为 {@link ApplicationContext}，供 handler 按需获取 Spring Bean</li>
 * </ul>
 */
public class SpringCommandHandler extends AbstractCommandHandler {

    public SpringCommandHandler(BotConfig config, BotLogger logger, File dataFolder,
                                ApplicationContext appCtx) {
        super(config, logger, dataFolder, appCtx);
    }

    @Override
    protected void registerPlatformServices(ModuleContextImpl ctx, BotConfig config,
                                             BotLogger logger, File dataFolder,
                                             Object platform) {
        // 独立运行无游戏控制台，命令执行退化为日志
        ctx.registerService(ConsoleExecutor.class, (ConsoleExecutor) (cmd, callback) -> {
            logger.info("[控制台命令] " + cmd);
            callback.accept("（独立运行模式无游戏控制台，命令已记录到日志）");
        });

        // 跨服控制台同样退化
        ctx.registerService(CrossServerConsole.class, (CrossServerConsole) (server, cmd, callback) -> {
            logger.info("[跨服命令] " + server + ": " + cmd);
            callback.accept("（独立运行模式不支持跨服命令执行）");
        });
    }

    @Override
    protected PlaceholderResolver createPlaceholders(BotConfig config, Object platform) {
        // 无 PAPI，返回简单实现：直接回调原文，由 BotRuntimeState 的 {bot} 处理
        return (text, event, callback) -> callback.accept(text);
    }
}
