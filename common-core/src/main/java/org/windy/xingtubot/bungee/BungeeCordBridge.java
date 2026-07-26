package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.windy.xingtubot.common.bridge.BridgeCodec;
import org.windy.xingtubot.common.bridge.CrossServerChannel;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * BungeeCord（大脑）侧的跨服桥：注册插件消息通道，处理子服上报，回应握手。
 * 纯跨服通信层，不含认证逻辑。与 VelocityBridge 对等。
 */
public class BungeeCordBridge implements Listener {

    private final ProxyServer proxy;
    private final Plugin plugin;
    private final String channel;
    private final Consumer<String> logger;
    private final Map<String, Consumer<String>> consoleCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> papiCallbacks = new ConcurrentHashMap<>();
    private CrossServerChannel redisChannel;
    private volatile java.util.Map<String, String> serversConfig = java.util.Collections.emptyMap();

    public BungeeCordBridge(ProxyServer proxy, Plugin plugin, String channel, Consumer<String> logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channel = channel;
        this.logger = logger;
        proxy.registerChannel(channel);
        proxy.getPluginManager().registerListener(plugin, this);
    }

    public void setServersConfig(java.util.Map<String, String> servers) {
        this.serversConfig = servers != null ? servers : java.util.Collections.emptyMap();
    }

    public void setRedisChannel(CrossServerChannel redisChannel) {
        this.redisChannel = redisChannel;

        // 订阅 Redis：收到子服经 Redis 上报的消息，复用与 PluginMessage 同一套处理；应答走 Redis 广播。

        redisChannel.setMessageHandler((fromServer, data) -> {
            BridgeCodec.Decoded msg = BridgeCodec.decode(data);
            if (msg != null) handleDecoded(msg, fromServer, this.redisChannel::broadcast);
        });
        // 广播子服注册表，让所有子服按端口自动发现自己的代理名
        broadcastServerRegistry();
    }

    /**
     * 通过 Redis 广播子服注册表（SERVER_REGISTRY），子服按端口匹配自动发现自己的代理名。
     * 格式："name1=host1:port1,name2=host2:port2"
     */
    private void broadcastServerRegistry() {
        if (redisChannel == null || serversConfig.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : serversConfig.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        String registry = sb.toString();
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.SERVER_REGISTRY, registry);
        redisChannel.broadcast(data);
        log("[跨服] 已广播子服注册表: " + registry);
    }

    public void dispatchConsole(String targetServerName, String command, Consumer<String> onResult) {
        String requestId = UUID.randomUUID().toString();
        consoleCallbacks.put(requestId, onResult);
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.DO_CONSOLE, targetServerName, requestId, command);
        // 有 Redis 信道：广播即可送达（即使目标子服无玩家在线），应答也经 Redis 回来。
        if (redisChannel != null) {
            redisChannel.broadcast(data);
            proxy.getScheduler().schedule(plugin, () -> consoleCallbacks.remove(requestId), 10, TimeUnit.SECONDS);
            return;
        }
        boolean sentAny = false;
        for (net.md_5.bungee.api.config.ServerInfo si : proxy.getServers().values()) {
            for (ProxiedPlayer p : si.getPlayers()) {
                if (p.getServer() != null) {
                    p.getServer().sendData(channel, data);
                    sentAny = true;
                    break;
                }
            }
        }
        if (!sentAny) {
            consoleCallbacks.remove(requestId);
            onResult.accept("⚠️ 没有可达的子服（子服需有玩家在线，或改用 redis 信道）");
            return;
        }
        // 10 秒超时清理
        proxy.getScheduler().schedule(plugin, () -> consoleCallbacks.remove(requestId), 10, TimeUnit.SECONDS);
    }

    public void resolvePapi(String playerName, String text, Consumer<String> onResult) {
        ProxiedPlayer p = proxy.getPlayer(playerName);
        if (p == null || p.getServer() == null) {
            onResult.accept(text);
            return;
        }
        String requestId = UUID.randomUUID().toString();
        papiCallbacks.put(requestId, onResult);
        byte[] data = BridgeCodec.encode(CrossServerProtocol.Type.PAPI_RESOLVE, requestId, playerName, text);
        try {
            p.getServer().sendData(channel, data);
        } catch (Exception e) {
            papiCallbacks.remove(requestId);
            onResult.accept(text);
            return;
        }
        // 2 秒超时
        proxy.getScheduler().schedule(plugin, () -> {
            Consumer<String> cb = papiCallbacks.remove(requestId);
            if (cb != null) cb.accept(text);
        }, 2, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getTag().equals(channel)) return;
        byte[] data = event.getData();
        if (data == null || data.length == 0) return;

        BridgeCodec.Decoded msg = BridgeCodec.decode(data);
        if (msg == null) return;

        // 应答原路返回：经 PluginMessage 来的就用发起方的 Server 回。
        final Object sender = event.getSender();
        final String proxyServerName = (sender instanceof Server) ? ((Server) sender).getInfo().getName() : null;
        handleDecoded(msg, proxyServerName, resp -> {
            if (sender instanceof Server) ((Server) sender).sendData(channel, resp);
        });
    }

    /**
     * 统一处理子服上报的消息（PluginMessage 与 Redis 共用）。
     *
     * @param proxyServerName 子服在代理端的注册名（PluginMessage 从 Server 获取；Redis 从消息头获取）
     * @param reply           把应答送回发起方子服的回调
     */
    private void handleDecoded(BridgeCodec.Decoded msg, String proxyServerName, Consumer<byte[]> reply) {
        switch (msg.type) {
            case WHO_IS_BOSS:
                // PluginMessage 模式：proxyServerName 从 ServerConnection 获取，是该子服在代理端的真实注册名。
                if (redisChannel == null) {
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
                Consumer<String> cb = (requestId == null) ? null : papiCallbacks.remove(requestId);
                if (cb != null) cb.accept(resolved == null ? "" : resolved);
                break;
            }

            case CONSOLE_RESULT: {
                String requestId = msg.field(0);
                String serverName = msg.field(1);
                String output = msg.field(2);
                Consumer<String> cb = (requestId == null) ? null : consoleCallbacks.remove(requestId);
                if (cb != null) cb.accept("【" + serverName + "】\n" + (output == null ? "" : output));
                break;
            }

            default:
                break;
        }
    }

    private void sendToPlayerServer(String player, byte[] data) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null && p.getServer() != null) {
            p.getServer().sendData(channel, data);
        }
    }

    private void log(String message) {
        if (logger != null) logger.accept(message);
    }
}
