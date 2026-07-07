package org.windy.xingtubot.common.module;

import org.windy.xingtubot.common.auth.PermissionService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.handler.HandlerRegistry;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.io.File;

/**
 * 附属扩展插件侧的 {@link ModuleContext}：把「核心共享部分」委托给 {@link XingtuBotHost}，
 * 「插件私有部分」用扩展自己的配置/日志/数据目录。
 *
 * <p>这样扩展里的 {@link BotModule} 用的还是和内置功能<b>完全相同</b>的 {@code ModuleContext} API
 * （{@code registry()/getService()/registerService()/permission()/messages()/platform()} 走核心，
 * {@code config()/logger()/dataFolder()} 走本插件），无需为「内置」与「外置」写两套代码。
 */
public final class ExtensionModuleContext implements ModuleContext {

    private final XingtuBotHost host;
    private final BotConfig config;
    private final BotLogger logger;
    private final File dataFolder;

    public ExtensionModuleContext(XingtuBotHost host, BotConfig config, BotLogger logger, File dataFolder) {
        this.host = host;
        this.config = config;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    // ===== 核心共享部分 → 委托宿主 =====

    @Override
    public HandlerRegistry registry() {
        return host.registry();
    }

    @Override
    public PlatformAdapter platform() {
        return host.platform();
    }

    @Override
    public PermissionService permission() {
        return host.permission();
    }

    @Override
    public void registerService(Class<?> type, Object instance) {
        host.registerService(type, instance);
    }

    @Override
    public <T> T getService(Class<T> type) {
        return host.getService(type);
    }

    @Override
    public Object getServiceObject(Class<?> type) {
        return host.getServiceObject(type);
    }

    // ===== 插件私有部分 → 用扩展自身的 =====

    @Override
    public BotConfig config() {
        return config;
    }

    @Override
    public BotLogger logger() {
        return logger;
    }

    @Override
    public File dataFolder() {
        return dataFolder;
    }
}
