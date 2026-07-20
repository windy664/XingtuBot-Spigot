package org.windy.xingtubot.common.bridge;

import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.redis.RedisManager;
import redis.clients.jedis.JedisPubSub;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * 基于 Redis Pub/Sub 的跨服通信通道。
 *
 * <p>消息格式：{@code [1字节来源类型][2字节serverName长度][serverName bytes][BridgeCodec data]}。
 * 来源类型：0=Velocity(大脑), 1=Spigot(手脚)。每个实例生成唯一 instanceId 过滤自己发的消息。
 */
public class RedisChannel implements CrossServerChannel {

    private static final String REDIS_CHANNEL = "xingtubot:bridge";

    private final RedisManager redis;
    private final String fallbackServerName;
    private final byte sourceType; // 0=velocity, 1=spigot
    private final String instanceId;
    private final BotLogger logger;

    private BiConsumer<String, byte[]> messageHandler;
    private JedisPubSub pubSub;
    private ExecutorService subscribeExecutor;
    private volatile boolean closed = false;

    /**
     * @param redis       Redis 管理器
     * @param serverName  本服 server-name（兜底值，握手后通过 BotRuntimeState 自动覆盖）
     * @param isSpigot    true=Spigot(手脚), false=Velocity(大脑)
     * @param logger      日志
     */
    public RedisChannel(RedisManager redis, String serverName, boolean isSpigot, BotLogger logger) {
        this.redis = redis;
        this.fallbackServerName = serverName;
        this.sourceType = (byte) (isSpigot ? 1 : 0);
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
        this.logger = logger;
        startSubscribe();
    }

    @Override
    public void send(String targetServer, byte[] data) {
        byte[] packed = pack(targetServer, data);
        redis.publish(REDIS_CHANNEL, new String(packed, StandardCharsets.ISO_8859_1));
    }

    @Override
    public void broadcast(byte[] data) {
        send("all", data);
    }

    @Override
    public void setMessageHandler(BiConsumer<String, byte[]> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void close() {
        closed = true;
        if (pubSub != null) {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscribeExecutor != null) {
            subscribeExecutor.shutdownNow();
        }
    }

    private void startSubscribe() {
        subscribeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "redis-bridge-sub");
            t.setDaemon(true);
            return t;
        });

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (closed) return;
                try {
                    byte[] raw = message.getBytes(StandardCharsets.ISO_8859_1);
                    Unpacked unpacked = unpack(raw);
                    if (unpacked == null) return;
                    // 过滤自己发的消息
                    if (instanceId.equals(unpacked.fromInstance)) return;
                    // 过滤同类型（避免大脑收大脑的消息，手脚收手脚的消息）
                    if (sourceType == unpacked.sourceType) return;

                    if (messageHandler != null) {
                        messageHandler.accept(unpacked.fromServer, unpacked.data);
                    }
                } catch (Exception e) {
                    logger.warn("[Redis] 处理消息失败: " + e.getMessage());
                }
            }
        };

        subscribeExecutor.submit(() -> {
            while (!closed) {
                try {
                    logger.info("[Redis] 订阅 " + REDIS_CHANNEL + " ...");
                    redis.subscribe(pubSub, REDIS_CHANNEL);
                } catch (Exception e) {
                    if (!closed) {
                        logger.warn("[Redis] 订阅断开，5秒后重连: " + e.getMessage());
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    }
                }
            }
        });
    }

    /** 运行时解析本服名称：优先代理端注册名（握手后可用），回退配置值。 */
    private String resolveServerName() {
        String proxy = org.windy.xingtubot.common.runtime.BotRuntimeState.getProxyServerName();
        return (proxy != null && !proxy.isEmpty()) ? proxy : fallbackServerName;
    }

    /**
     * 打包：[instanceId(8bytes)][sourceType(1byte)][targetServerLen(2bytes)][targetServer][data]
     */
    private byte[] pack(String targetServer, byte[] data) {
        byte[] instanceBytes = instanceId.getBytes(StandardCharsets.UTF_8);
        byte[] serverBytes = (targetServer != null ? targetServer : "all").getBytes(StandardCharsets.UTF_8);
        byte[] packed = new byte[8 + 1 + 2 + serverBytes.length + data.length];
        int pos = 0;
        System.arraycopy(instanceBytes, 0, packed, pos, 8); pos += 8;
        packed[pos++] = sourceType;
        packed[pos++] = (byte) (serverBytes.length >> 8);
        packed[pos++] = (byte) (serverBytes.length & 0xFF);
        System.arraycopy(serverBytes, 0, packed, pos, serverBytes.length); pos += serverBytes.length;
        System.arraycopy(data, 0, packed, pos, data.length);
        return packed;
    }

    private Unpacked unpack(byte[] raw) {
        if (raw.length < 11) return null;
        int pos = 0;
        String fromInstance = new String(raw, pos, 8, StandardCharsets.UTF_8); pos += 8;
        byte sourceType = raw[pos++];
        int serverLen = ((raw[pos] & 0xFF) << 8) | (raw[pos + 1] & 0xFF); pos += 2;
        if (pos + serverLen > raw.length) return null;
        String fromServer = new String(raw, pos, serverLen, StandardCharsets.UTF_8); pos += serverLen;
        byte[] data = new byte[raw.length - pos];
        System.arraycopy(raw, pos, data, 0, data.length);
        return new Unpacked(fromInstance, sourceType, fromServer, data);
    }

    private static class Unpacked {
        final String fromInstance;
        final byte sourceType;
        final String fromServer;
        final byte[] data;
        Unpacked(String fromInstance, byte sourceType, String fromServer, byte[] data) {
            this.fromInstance = fromInstance;
            this.sourceType = sourceType;
            this.fromServer = fromServer;
            this.data = data;
        }
    }
}
