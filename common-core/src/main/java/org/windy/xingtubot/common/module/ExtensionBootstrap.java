package org.windy.xingtubot.common.module;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.File;

/**
 * 附属扩展插件的统一启用/禁用样板。
 *
 * <p>每个扩展插件（Bukkit 主类 / Velocity 主类）定位到 {@link XingtuBotHost} 后，只需调
 * {@link #enable} 即可：本类负责总开关门控、组装 {@link ExtensionModuleContext}、调
 * {@link BotModule#onEnable}，并把模块回交给调用方在插件 {@code onDisable} 时 {@link #disable}。
 *
 * <p>这样每个扩展主类都瘦到十几行，且内外置模块共用同一套 {@code BotModule} 实现。
 */
public final class ExtensionBootstrap {

    private ExtensionBootstrap() {
    }

    /**
     * 启用一个扩展模块。
     *
     * @param host       核心宿主（为 null 时跳过并告警——通常意味着核心未安装/未就绪）
     * @param module     扩展的 {@link BotModule} 实现
     * @param config     扩展插件自己的配置
     * @param logger     扩展插件自己的日志
     * @param dataFolder 扩展插件自己的数据目录
     * @return 已启用的模块（供调用方在 onDisable 时传回 {@link #disable}）；未启用返回 null
     */
    public static BotModule enable(XingtuBotHost host, BotModule module,
                                   BotConfig config, BotLogger logger, File dataFolder) {
        if (module == null) return null;
        if (host == null) {
            if (logger != null) {
                logger.warn("[" + safeName(module) + "] 未找到昕途核心(XingtuBotHost)，扩展未加载。"
                        + "请确认已安装并先于本扩展启动 XingtuBot 核心。");
            }
            return null;
        }
        // 总开关：扩展自身配置里的 module-<name>-enable（默认 true）。
        String key = "module-" + safeName(module) + "-enable";
        if (!config.getBoolean(key, true)) {
            if (logger != null) logger.info("[" + safeName(module) + "] 已被配置禁用(" + key + ": false)。");
            return null;
        }
        try {
            ModuleContext ctx = new ExtensionModuleContext(host, config, logger, dataFolder);
            module.onEnable(ctx);
            if (logger != null) logger.info("[" + safeName(module) + "] 扩展已加载。");
            return module;
        } catch (Throwable t) {
            if (logger != null) logger.warn("[" + safeName(module) + "] 扩展启用失败: " + t);
            return null;
        }
    }

    /** 禁用一个先前由 {@link #enable} 返回的模块。 */
    public static void disable(BotModule module) {
        if (module == null) return;
        try {
            module.onDisable();
        } catch (Throwable ignored) {
        }
    }

    private static String safeName(BotModule m) {
        try {
            String n = m.name();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) {
        }
        return m.getClass().getSimpleName();
    }
}
