package org.windy.xingtubot.common.handler;

import org.windy.xingtubot.common.auth.PermissionService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.platform.BotLogger;

/**
 * Handler 初始化上下文：向 MessageHandler 注入平台无关的依赖。
 * 平台特定对象（如 ProxyServer、VelocityBridge）通过 {@link #getPlatform()} 获取。
 */
public class HandlerContext {

    private final BotConfig config;
    private final BotLogger logger;
    private final PermissionService permission;
    private final Object platform; // 平台特定对象：Velocity = ProxyServer，Spigot = JavaPlugin

    public HandlerContext(BotConfig config, BotLogger logger, PermissionService permission, Object platform) {
        this.config = config;
        this.logger = logger;
        this.permission = permission;
        this.platform = platform;
    }

    public BotConfig getConfig() {
        return config;
    }

    public BotLogger getLogger() {
        return logger;
    }

    public PermissionService getPermission() {
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
