package org.windy.xingtubot.common.module.capability;

import java.util.function.Consumer;

/**
 * 平台能力：把控制台命令派发到指定子服执行并回传输出（跨服控服）。
 *
 * <ul>
 *   <li>Velocity：由 {@code VelocityBridge.dispatchConsole} 经 PluginMessage 广播到目标子服。</li>
 *   <li>Spigot：单机无跨服语义，通常为 null。</li>
 * </ul>
 */
public interface CrossServerConsole {

    /**
     * 在目标子服执行控制台命令。
     *
     * @param server  目标子服名（约定 "all" 表示广播全部）
     * @param command 完整命令
     * @param output  输出回调
     */
    void exec(String server, String command, Consumer<String> output);
}
