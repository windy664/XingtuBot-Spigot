package org.windy.xingtubot.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import org.windy.xingtubot.common.bridge.BridgeCodec;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Velocity（大脑）侧的跨服桥：注册插件消息通道，处理子服上报，回应握手。
 *
 * <p>子服上报 → 这里：WHO_IS_BOSS（回 I_AM_BOSS 宣告主导）、PAPI_RESULT、CONSOLE_RESULT。
 *
 * <p>auth 相关逻辑（evaluateOnJoin、登录提醒、绑定检查等）已移至 xt-auth 的 AuthVelocityPlugin。
 */
public class VelocityBridge {

    private final ProxyServer proxy;
    private final Object plugin;
    private final ChannelIdentifier channel;
    private final Consumer<String> logger;
    private final java.util.Map<String, Consumer<String>> consoleCallbacks =
            new ConcurrentHashMap<>();
    private final java.util.Map<String, Consumer<String>> papiCallbacks =
            new ConcurrentHashMap<>();

    // Redis 跨服信道（可选，xt-auth 按 config 注入）。非 null 时大脑经它收发，免去「需在线玩家当载体」。
    private volatile org.windy.xingtubot.common.bridge.CrossServerChannel redisChannel;
    // 子服注册表（name→address），用于 Redis 模式下广播 SERVER_REGISTRY 让子服自动发现代理名。
    private volatile java.util.Map<String, String> serversConfig = java.util.Collections.emptyMap();

    public VelocityBridge(ProxyServer proxy, Object plugin, ChannelIdentifier channel,
                          Consumer<String> logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channel = channel;
        this.logger = logger;
        proxy.getChannelRegistrar().register(channel);
        proxy.getEventManager().register(plugin, this);
    }

    /** 设置子服注册表（name→address），供 SERVER_REGISTRY 广播使用。 */
    public void setServersConfig(java.util.Map<String, String> servers) {
        this.serversConfig = servers != null ? servers : java.util.Collections.emptyMap();
    }

    public void setRedisChannel(org.windy.xingtubot.common.bridge.CrossServerChannel redisChannel) {
        this.redisChannel = redisChannel;
        // 订阅 Redis：收到子服经 Redis 上报的消息后，复用与 PluginMessage 同一套处理逻辑，
        // 应答走 Redis 广播（无 ServerConnection 可回）。子服只认 sourceType 不同的消息，不会回环。
        redisChannel.setMessageHandler((fromServer, data) -> {
            BridgeCodec.Decoded msg = BridgeCodec.decode(data);
            if (msg != null) handleDecoded(msg, fromServer, this.redisChannel::broadcast);
        });
        // 广播子服注册表，让所有子服按端口自动发现自己的代理名
        broadcastServerRegistry();
    }

    /**
     * 通过 Redis 广播子服注册表（SERVER_REGISTRY），子服按端口匹配自动发现自己的代理名。
     *
     * <p>格式："name1=host1:port1,name2=host2:port2"
     */
    private void broadcastServerRegistry() {
        org.windy.xingtubot.common.bridge.CrossServerChannel rc = this.redisChannel;
        if (rc == null || serversConfig.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : serversConfig.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        String registry = sb.toString();
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.SERVER_REGISTRY, registry);
        rc.broadcast(data);
        log("[跨服] 已广播子服注册表: " + registry);
    }

    public void dispatchConsole(String targetServerName, String command,
                                Consumer<String> onResult) {
        String requestId = java.util.UUID.randomUUID().toString();
        consoleCallbacks.put(requestId, onResult);
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.DO_CONSOLE,
                targetServerName, requestId, command);
        // 有 Redis 信道：广播即可送达（即使目标子服无玩家在线），应答也经 Redis 回来。
        org.windy.xingtubot.common.bridge.CrossServerChannel rc = this.redisChannel;
        if (rc != null) {
            log("[跨服] dispatchConsole → Redis broadcast, target=" + targetServerName + " cmd=" + command);
            rc.broadcast(data);
            scheduleCleanup(requestId);
            return;
        }
        log("[跨服] dispatchConsole → PluginMessage 回退（无 Redis 信道）, target=" + targetServerName);
        boolean sentAny = false;
        for (com.velocitypowered.api.proxy.server.RegisteredServer rs : proxy.getAllServers()) {
            try {
                if (rs.sendPluginMessage(channel, data)) sentAny = true;
            } catch (Exception ignored) {
            }
        }
        if (!sentAny) {
            consoleCallbacks.remove(requestId);
            onResult.accept("⚠️ 没有可达的子服（子服需有玩家在线，插件消息才能送达；或改用 redis 信道）");
            return;
        }
        scheduleCleanup(requestId);
    }

    public void resolvePapi(String playerName, String text, Consumer<String> onResult) {
        java.util.Optional<Player> p =
                playerName == null ? java.util.Optional.empty() : proxy.getPlayer(playerName);
        if (!p.isPresent() || !p.get().getCurrentServer().isPresent()) {
            onResult.accept(text);
            return;
        }
        String requestId = java.util.UUID.randomUUID().toString();
        papiCallbacks.put(requestId, onResult);
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.PAPI_RESOLVE, requestId, playerName, text);
        try {
            p.get().getCurrentServer().get().sendPluginMessage(channel, data);
        } catch (Exception e) {
            papiCallbacks.remove(requestId);
            onResult.accept(text);
            return;
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            Consumer<String> cb = papiCallbacks.remove(requestId);
            if (cb != null) cb.accept(text);
        });
        t.setDaemon(true);
        t.start();
    }

    private void scheduleCleanup(String requestId) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
            consoleCallbacks.remove(requestId);
        });
        t.setDaemon(true);
        t.start();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection)) return;

        BridgeCodec.Decoded msg = BridgeCodec.decode(event.getData());
        if (msg == null) return;

        // 应答原路返回：经 PluginMessage 来的就用发起方的 ServerConnection 回。
        final ServerConnection src = (ServerConnection) event.getSource();
        final String proxyServerName = src.getServerInfo().getName();
        handleDecoded(msg, proxyServerName, resp -> src.sendPluginMessage(channel, resp));
    }

    /**
     * 统一处理子服上报的消息（PluginMessage 与 Redis 共用）。
     *
     * @param proxyServerName 子服在代理端的注册名（PluginMessage 从 ServerConnection 获取；Redis 模式可为空）
     * @param reply           把应答送回发起方子服的回调
     */
    private void handleDecoded(BridgeCodec.Decoded msg, String proxyServerName, Consumer<byte[]> reply) {
        switch (msg.type) {
            case WHO_IS_BOSS:
                // PluginMessage 模式：proxyServerName 从 ServerConnection 获取，是该子服在代理端的真实注册名。
                if (this.redisChannel == null) {
                    reply.accept(BridgeCodec.encode(CrossServerProtocol.Type.I_AM_BOSS,
                            proxyServerName != null ? proxyServerName : ""));
                } else {
                    // Redis 模式：fromServer="all" 无法确定是哪个子服发的。
                    // 回 I_AM_BOSS（空串）让 xt-auth 的 brainConfirmed 生效（绑定检查/玩家锁依赖它）。
                    // 同时广播 SERVER_REGISTRY 让子服按端口自动发现代理名。
                    reply.accept(BridgeCodec.encode(CrossServerProtocol.Type.I_AM_BOSS, ""));
                    broadcastServerRegistry();
                }
                break;

            case PAPI_RESULT: {
                String requestId = msg.field(0);
                String resolved = msg.field(1);
                Consumer<String> cb =
                        (requestId == null) ? null : papiCallbacks.remove(requestId);
                if (cb != null) cb.accept(resolved == null ? "" : resolved);
                break;
            }

            case CONSOLE_RESULT: {
                String requestId = msg.field(0);
                String serverName = msg.field(1);
                String output = msg.field(2);
                Consumer<String> cb = (requestId == null) ? null
                        : consoleCallbacks.remove(requestId);
                if (cb != null) {
                    cb.accept("【" + serverName + "】\n" + (output == null ? "" : output));
                }
                break;
            }

            default:
                break;
        }
    }

    /** 把数据发送给指定玩家当前所在的子服（Player 必须在线且已连接子服）。 */
    private void sendToPlayerServer(String player, byte[] data) {
        proxy.getPlayer(player)
                .flatMap(p -> p.getCurrentServer())
                .ifPresent(sc -> sc.sendPluginMessage(channel, data));
    }

    private void log(String m) {
        if (logger != null) logger.accept(m);
    }
}
