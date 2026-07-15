package org.windy.xingtubot.common.module;

import org.windy.xingtubot.common.handler.PermissionChecker;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.handler.HandlerRegistry;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModuleContext 的默认实现。
 *
 * <p>由 Spigot/Velocity 平台侧构造，注入平台特定的组件后传给各模块。
 *
 * <p>同时实现 {@link XingtuBotHost}：平台侧把<b>同一个</b>实例既用于内置模块的 {@code loadAll}，
 * 又作为宿主暴露给外部附属扩展插件，从而内置与外置共享同一条服务总线（{@code services}）与注册中心。
 */
public class ModuleContextImpl implements ModuleContext, XingtuBotHost {

    private final HandlerRegistry registry;
    private final BotConfig config;
    private final BotLogger logger;
    private final PlatformAdapter platform;
    private final PermissionChecker permission;
    private final File dataFolder;
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
    private final Map<String, Object> namedServices = new ConcurrentHashMap<>();
    // 部署拓扑：本实例是否为大脑（master）。由平台侧在启动时算定后 setBrain 注入；扩展只读 isBrain()。
    private volatile boolean brain = false;

    public ModuleContextImpl(HandlerRegistry registry, BotConfig config, BotLogger logger,
                             PlatformAdapter platform, PermissionChecker permission,
                             File dataFolder) {
        this.registry = registry;
        this.config = config;
        this.logger = logger;
        this.platform = platform;
        this.permission = permission;
        this.dataFolder = dataFolder;
    }

    @Override
    public HandlerRegistry registry() {
        return registry;
    }

    @Override
    public BotConfig config() {
        return config;
    }

    @Override
    public BotLogger logger() {
        return logger;
    }

    @Override
    public PlatformAdapter platform() {
        return platform;
    }

    @Override
    public PermissionChecker permission() {
        return permission;
    }

    @Override
    public File dataFolder() {
        return dataFolder;
    }

    @Override
    public void registerService(Class<?> type, Object instance) {
        services.put(type, instance);
    }

    @Override
    public void registerService(String key, Object instance) {
        if (key != null && instance != null) {
            namedServices.put(key, instance);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getService(Class<T> type) {
        return (T) services.get(type);
    }

    @Override
    public Object getServiceObject(Class<?> type) {
        return services.get(type);
    }

    @Override
    public Object getServiceObject(String key) {
        return key == null ? null : namedServices.get(key);
    }

    /** 平台侧设置部署拓扑（本实例是否大脑）。须在扩展插件读取 {@link #isBrain()} 之前调用。 */
    public void setBrain(boolean brain) {
        this.brain = brain;
    }

    @Override
    public boolean isBrain() {
        return brain;
    }
}
