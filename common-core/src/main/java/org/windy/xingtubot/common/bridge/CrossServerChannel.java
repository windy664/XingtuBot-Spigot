package org.windy.xingtubot.common.bridge;

import java.util.function.BiConsumer;

/**
 * 跨服通信通道抽象：PluginMessage 或 Redis。
 *
 * <p>两端（Velocity 大脑 / Spigot 手脚）共用同一套 {@link CrossServerProtocol} 消息类型，
 * 通道只负责「把字节送到对端」。
 */
public interface CrossServerChannel {

    /**
     * 发送消息到指定服。
     *
     * @param targetServer 目标服 server-name，或 "all" 广播
     * @param data         BridgeCodec 编码的数据
     */
    void send(String targetServer, byte[] data);

    /**
     * 广播消息到所有服。
     */
    void broadcast(byte[] data);

    /**
     * 设置唯一的消息回调（覆盖之前的）。
     *
     * @param handler (fromServer, data) — fromServer 是发送方 server-name
     */
    void setMessageHandler(BiConsumer<String, byte[]> handler);

    /**
     * 追加一个消息回调（与已有回调共存，所有回调都会被调用）。
     *
     * <p>用于多个模块各自处理不同类型的消息（如 core 处理 DO_CONSOLE，xt-auth 处理绑定）。
     *
     * @param handler (fromServer, data)
     */
    default void addMessageHandler(BiConsumer<String, byte[]> handler) {
        // 默认实现：退化为 setMessageHandler（只有一个处理器）
        setMessageHandler(handler);
    }

    /** 关闭通道，释放资源。 */
    default void close() {}

    /**
     * 获取本通道的 server-name（如 "shelter"、"lobby"）。
     *
     <p>Redis 模式下用于握手中告诉对端自己叫什么名字；PluginMessage 模式通常不需要（从 ServerConnection 获取）。
     */
    default String getServerName() { return null; }
}
