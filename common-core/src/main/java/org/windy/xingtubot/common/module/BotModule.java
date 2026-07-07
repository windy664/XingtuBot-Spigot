package org.windy.xingtubot.common.module;

/**
 * 功能模块 SPI 接口。
 *
 * <p>每个独立功能（天气/搜索/AI/自定义问答等）实现此接口，
 * 由平台侧（Spigot/Velocity）统一加载和初始化。
 */
public interface BotModule {

    /** 模块名（日志/调试用）。 */
    String name();

    /**
     * 加载优先级：数值越小越先加载。默认 100。
     *
     * <p>「服务提供型」模块（如翻译/TTS，会经 {@link ModuleContext#registerService}
     * 提供共享服务给其他模块消费）应返回较小值，确保在消费方之前完成注册。
     */
    default int loadPriority() {
        return 100;
    }

    /** 模块启用：在此注册 handler/command、初始化服务。 */
    void onEnable(ModuleContext ctx);

    /** 模块禁用（可选）。释放资源、停止定时任务等。 */
    default void onDisable() {
    }
}
