package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.binding.BindingService;
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
 * 与 VelocityBridge 功能对等。
 */
public class BungeeCordBridge implements Listener {

    private final ProxyServer proxy;
    private final Plugin plugin;
    private final String channel;
    private final AuthAdapter auth;
    private final Consumer<String> logger;
    private final Map<String, Consumer<String>> consoleCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> papiCallbacks = new ConcurrentHashMap<>();
    private CrossServerChannel redisChannel;
    private Consumer<String> onUnboundJoin;

    /** xt-auth / 主插件提供 BindingService（惰性解析；可能为 null = 无白名单）。 */
    private volatile java.util.function.Supplier<BindingService> serviceProvider;

    public BungeeCordBridge(ProxyServer proxy, Plugin plugin, String channel,
                            AuthAdapter auth, Consumer<String> logger) {
        this(proxy, plugin, channel, auth, logger, (java.util.function.Supplier<BindingService>) null);
    }

    /** 直接传入 BindingService（主插件内白名单场景）：内部包成 supplier。 */
    public BungeeCordBridge(ProxyServer proxy, Plugin plugin, String channel,
                            BindingService service, AuthAdapter auth, Consumer<String> logger) {
        this(proxy, plugin, channel, auth, logger, service == null ? null : () -> service);
    }

    public BungeeCordBridge(ProxyServer proxy, Plugin plugin, String channel,
                            AuthAdapter auth, Consumer<String> logger,
                            java.util.function.Supplier<BindingService> serviceProvider) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.channel = channel;
        this.auth = auth;
        this.logger = logger;
        this.serviceProvider = serviceProvider;
        proxy.registerChannel(channel);
        proxy.getPluginManager().registerListener(plugin, this);
    }

    /** xt-auth 就绪后注入 BindingService 供给者（惰性）。 */
    public void setServiceProvider(java.util.function.Supplier<BindingService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    /** 认证适配器（DO_LOGIN/DO_REGISTER 下发通道）：供 xt-auth 注入到 BindingService。 */
    public AuthAdapter getAuthAdapter() {
        return auth;
    }

    /** 解析当前 BindingService（无白名单时为 null）。 */
    private BindingService service() {
        java.util.function.Supplier<BindingService> sp = this.serviceProvider;
        return sp != null ? sp.get() : null;
    }

    /** @deprecated 昵称统一走 {@link org.windy.xingtubot.common.BotIdentity}，不再生效。 */
    @Deprecated
    public void setBotName(String botName) { /* no-op */ }
    public void setRedisChannel(CrossServerChannel redisChannel) {
        this.redisChannel = redisChannel;
        // 订阅 Redis：收到子服经 Redis 上报的消息，复用与 PluginMessage 同一套处理；应答走 Redis 广播。
        redisChannel.setMessageHandler((fromServer, data) -> {
            BridgeCodec.Decoded msg = BridgeCodec.decode(data);
            if (msg != null) handleDecoded(msg, this.redisChannel::broadcast);
        });
    }
    public void setOnUnboundJoin(Consumer<String> callback) { this.onUnboundJoin = callback; }

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
        handleDecoded(msg, resp -> {
            if (sender instanceof Server) ((Server) sender).sendData(channel, resp);
        });
    }

    /**
     * 统一处理子服上报的消息（PluginMessage 与 Redis 共用）。
     *
     * @param reply 把应答送回发起方子服的回调（PluginMessage 用 Server；Redis 用广播）。
     */
    private void handleDecoded(BridgeCodec.Decoded msg, Consumer<byte[]> reply) {
        switch (msg.type) {
            case WHO_IS_BOSS:
                reply.accept(BridgeCodec.encode(CrossServerProtocol.Type.I_AM_BOSS));
                break;

            case PLAYER_JOIN:
                // 进服判定由主插件 onPostLogin 驱动，不依赖子服上报
                break;

            case DECLARE_QQ: {
                // 子服上报：玩家在游戏内输入了 QQ 号 → 下载该 QQ 头像登记并回发提示（与 Velocity 对等）。
                String player = msg.field(0);
                String qq = msg.field(1);
                BindingService svc = service();
                if (svc == null) break;
                proxy.getScheduler().runAsync(plugin, () -> {
                    try {
                        BindingService.Result r = svc.declareQQ(player, qq);
                        if (r.success) {
                            // 进入「去群里发『绑定』」阶段：清掉子服的等待输 QQ 态。
                            sendToPlayerServer(player, BridgeCodec.encode(CrossServerProtocol.Type.CLEAR_QQ, player));
                        }
                        // 提示/报错文案回发给玩家（加群二维码 Bungee 在进服时已由 onUnboundJoin 给出）。
                        auth.messagePlayer(player, r.message);
                    } catch (Exception e) {
                        if (logger != null) logger.accept("处理 DECLARE_QQ 异常: " + e.getMessage());
                    }
                });
                break;
            }

            case PLAYER_QUIT: {
                // 不清待验证记录：玩家切服/掉线后仍可在 TTL 内去群里发「绑定」绑成（按 TTL 自动过期）。
                break;
            }

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

            case QUERY_BINDING_BY_OPENID: {
                // 子服查询：某 openid 绑定了哪个玩家。查到后把玩家名应答回去（与 Velocity 对等）。
                String requestId = msg.field(0);
                String openid = msg.field(1);
                if (requestId == null) break;
                String playerName = "";
                BindingService svc = service();
                if (svc != null && openid != null) {
                    java.util.List<String> players = svc.getStore().getPlayersByOpenid(openid);
                    if (!players.isEmpty()) playerName = players.get(0);
                }
                reply.accept(BridgeCodec.encode(CrossServerProtocol.Type.BINDING_RESULT, requestId, playerName));
                break;
            }

            default:
                break;
        }
    }

    /** 进服三态判定：未绑定→引导输QQ；已绑定→自动免登解锁。 */
    public void evaluateOnJoin(String player) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p == null || !p.isConnected()) return;
        BindingService service = service();
        if (service == null) return; // 无白名单服务，跳过进服判定
        if (!service.isPlayerBound(player)) {
            sendToPlayerServer(player, BridgeCodec.encode(CrossServerProtocol.Type.NEED_QQ, player));
            auth.titlePlayer(player, "§6§l欢迎来到本服", "§f请在聊天框输入 QQ 号开始白名单绑定");
            auth.messagePlayer(player, "§e欢迎！请在聊天框输入你的 §bQQ号 §e完成白名单绑定");
            if (onUnboundJoin != null) onUnboundJoin.accept(player);
        } else if (!service.isLoggedInSession(player)) {
            service.clearExpired();
            if (service.hasPending(player)) {
                auth.login(player);
                service.clearSession(player);
                auth.titlePlayer(player, "§a§l登录成功", "§f欢迎回来！");
            } else {
                sendToPlayerServer(player, BridgeCodec.encode(CrossServerProtocol.Type.NEED_QQ, player));
                auth.messagePlayer(player, "§e请在群里发送 §b@机器人 登录 §e完成白名单验证");
            }
        }
    }

    private void sendToPlayerServer(String player, byte[] data) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null && p.getServer() != null) {
            p.getServer().sendData(channel, data);
        }
    }
}
