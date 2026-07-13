package org.windy.xingtubot.common.module;

import org.windy.xingtubot.common.handler.PermissionChecker;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.handler.HandlerRegistry;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.io.File;

/**
 * 模块上下文：提供核心框架能力 + 跨模块服务查询。
 *
 * <p>功能模块通过此接口获取注册中心、配置、日志等，
 * 也可通过 {@link #getService(Class)} 获取其他模块注册的共享服务（如翻译/AI）。
 */
public interface ModuleContext {

    /** 消息处理器注册中心。 */
    HandlerRegistry registry();

    /** 机器人配置。 */
    BotConfig config();

    /** 日志。 */
    BotLogger logger();

    /** 平台适配器（Bukkit/Velocity）。 */
    PlatformAdapter platform();

    /** 超管权限服务。 */
    PermissionChecker permission();

    /** 数据目录。 */
    File dataFolder();

    // ==================== 跨模块服务注册/查询 ====================

    /**
     * 注册一个共享服务实例（如翻译、AI），供其他模块通过 {@link #getService(Class)} 获取。
     */
    void registerService(Class<?> type, Object instance);

    /**
     * 获取其他模块注册的共享服务。未注册返回 null。
     */
    <T> T getService(Class<T> type);

    /**
     * 按 Class 对象获取共享服务（返回 Object），供跨 classloader 场景使用。
     *
     * <p>扩展插件无法直接引用主插件 classloader 中的类型时，
     * 可通过 {@code Class.forName(...)} 反射获取 Class 对象后调用本方法。
     */
    Object getServiceObject(Class<?> type);
}
