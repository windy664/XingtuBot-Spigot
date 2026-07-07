package org.windy.xingtubot.common;

import java.util.function.BooleanSupplier;

/**
 * 框架级「调试模式」的单一来源（对应核心 config 的 {@code debug}）。
 *
 * <p>核心在启动时把读 config 的 supplier {@link #bind(BooleanSupplier) 绑}进来——之后 {@link #isOn()}
 * 每次都<b>实时</b>取值，故 {@code /xtb debug} 在线切换也会立即生效。
 *
 * <p>放在 common-core（被 bundle shade、各扩展 compileOnly），运行期由 bundle 的 classloader 持有
 * <b>同一份</b>静态实例——附属插件（如 xt-modquery 的 MCMOD 爬取）直接 {@link #isOn()} 即可跟随框架
 * 调试开关，无需自己再读一遍核心 config。与 {@link BotIdentity} 同套跨插件可见机制。
 */
public final class DebugFlag {

    private static volatile BooleanSupplier supplier = () -> false;

    private DebugFlag() {
    }

    /** 由核心启动时绑定（通常为 {@code () -> config.getBoolean("debug", false)}）。 */
    public static void bind(BooleanSupplier s) {
        if (s != null) supplier = s;
    }

    /** 当前是否开启调试模式（实时取值）。绑定前/异常时返回 false。 */
    public static boolean isOn() {
        try {
            return supplier.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }
}
