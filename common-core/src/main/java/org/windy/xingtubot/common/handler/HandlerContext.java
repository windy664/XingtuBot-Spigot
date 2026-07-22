package org.windy.xingtubot.common.handler;

import org.windy.xingtubot.common.handler.PermissionChecker;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.platform.BotLogger;

/**
 * Handler 初始化上下文：向 BotMessageHandler 注入平台无关的依赖。
 * 平台特定对象（如 ProxyServer、VelocityBridge）通过 {@link #getPlatform()} 获取。
 */
public class HandlerContext {

    private final BotConfig config;
    private final BotLogger logger;
    private final PermissionChecker permission;
    private final Object platform; // 平台特定对象：Velocity = ProxyServer，Spigot = JavaPlugin
    private final HandlerRegistry registry;

    public HandlerContext(BotConfig config, BotLogger logger, PermissionChecker permission,
                         Object platform, HandlerRegistry registry) {
        this.config = config;
        this.logger = logger;
        this.permission = permission;
        this.platform = platform;
        this.registry = registry;
    }

    /** 注册中心（供需要自查询的 handler 如 MenuHandler 使用）。 */
    public HandlerRegistry getRegistry() {
        return registry;
    }

    public BotConfig getConfig() {
        return config;
    }

    public BotLogger getLogger() {
        return logger;
    }

    public PermissionChecker getPermission() {
        return permission;
    }

    /**
     * 获取平台特定对象。Velocity 端为 ProxyServer，Spigot 端为 JavaPlugin。
     * Handler 内部按需转型，common 层不依赖平台类型。
     */
    @SuppressWarnings("unchecked")
    public <T> T getPlatform() {
        return (T) platform;
    }
}
