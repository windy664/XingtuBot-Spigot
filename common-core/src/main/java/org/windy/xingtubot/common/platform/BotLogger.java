package org.windy.xingtubot.common.platform;

/**
 * 平台无关的日志接口，供 common 模块内的服务使用，
 * 避免直接依赖 Bukkit / Velocity 的日志实现。
 */
public interface BotLogger {
    void info(String msg);

    void warn(String msg);
}
