package org.windy.xingtubot.common.runtime;

import java.util.function.BooleanSupplier;

/**
 * Internal runtime state shared by core and extensions through the common-core classloader.
 */
public final class BotRuntimeState {

    private static volatile String botName = "机器人";
    private static volatile String proxyServerName; // 子服在代理端的注册名（由 I_AM_BOSS 握手设置）
    private static volatile BooleanSupplier debugSupplier = () -> false;

    private BotRuntimeState() {
    }

    public static String getBotName() {
        return botName;
    }

    public static void setBotName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            botName = name.trim();
        }
    }

    public static void bindDebug(BooleanSupplier supplier) {
        if (supplier != null) {
            debugSupplier = supplier;
        }
    }

    public static boolean isDebugEnabled() {
        try {
            return debugSupplier.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 子服在代理端的注册名（如 "lobby"、"shelter"）。
     * 由 I_AM_BOSS 握手自动设置，无需手动配置。
     * 未获取到时返回 null。
     */
    public static String getProxyServerName() {
        return proxyServerName;
    }

    public static void setProxyServerName(String name) {
        if (name != null && !name.trim().isEmpty()) {
            proxyServerName = name.trim();
        }
    }
}
