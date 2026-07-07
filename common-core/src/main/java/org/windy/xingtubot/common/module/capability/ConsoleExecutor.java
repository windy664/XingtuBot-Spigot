package org.windy.xingtubot.common.module.capability;

import java.util.function.Consumer;

/**
 * 平台能力：在本服控制台执行一条命令并回传输出。
 *
 * <p>由平台侧实现并经 {@link org.windy.xingtubot.common.module.ModuleContext#registerService}
 * 注册；功能模块（如 module-admin 的控制台命令、自定义命令的控制台执行分支）经
 * {@link org.windy.xingtubot.common.module.ModuleContext#getService} 获取，为 null 时跳过该能力。
 *
 * <ul>
 *   <li>Spigot：调度到主线程 dispatchCommand + CapturingConsoleSender 捕获输出。</li>
 *   <li>Velocity：本地无控制台执行语义，通常为 null（跨服执行见 {@link CrossServerConsole}）。</li>
 * </ul>
 */
public interface ConsoleExecutor {

    /**
     * 执行控制台命令。
     *
     * @param command 完整命令（不含前导斜杠语义由实现决定）
     * @param output  输出回调；实现可多次回调或合并后一次回调
     */
    void exec(String command, Consumer<String> output);
}
