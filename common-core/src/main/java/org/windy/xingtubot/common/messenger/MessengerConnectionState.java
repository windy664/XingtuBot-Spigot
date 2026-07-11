package org.windy.xingtubot.common.messenger;

/**
 * 平台消息适配器连接状态枚举。
 *
 * <p>替代过去 {@code QqOpenApiClient 引用是否为 null} 的就绪判据，
 * 支持更精确的连接态（如 WS 断连后进入 RECONNECTING，此时 apiClient 引用非 null 但不可用）。
 */
public enum MessengerConnectionState {

    /** 未启动或已关闭。 */
    DISCONNECTED,
    /** 正在建立连接（首次连出）。 */
    CONNECTING,
    /** 断连后正在指数退避重连。 */
    RECONNECTING,
    /** 连接已建立并可用。 */
    READY;

    /** 连接是否处于可用状态（仅 READY 返回 true）。 */
    public boolean isReady() {
        return this == READY;
    }
}
