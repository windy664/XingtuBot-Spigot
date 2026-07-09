package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.windy.xingtubot.bukkit.XingtuBot;
import org.windy.xingtubot.bukkit.util.PapiResolver;
import org.windy.xingtubot.common.bridge.BridgeCodec;
import org.windy.xingtubot.common.bridge.CrossServerChannel;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;
import org.windy.xingtubot.common.lock.LockState;
import org.windy.xingtubot.common.whitelist.LockMessages;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 白名单「手脚」模式（slave，自研锁版，无 AuthMe）。
 *
 * <p>机器人 webhook 与头像比对都在 Velocity（大脑）。本类只做：
 * 上报玩家加入/输QQ/退出给大脑；执行大脑下发的锁定控制（NEED_QQ/CLEAR_QQ/DO_REGISTER/DO_LOGIN）；
 * 本地用 {@link PlayerLockListener} 冻结未登录玩家。
 */
public class SpigotBridge implements Listener, PluginMessageListener {

    private static final String CH = CrossServerProtocol.CHANNEL;
    private static SpigotBridge instance;

    private final XingtuBot plugin;
    private final LockState lockState = new LockState();
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();
    private volatile boolean brainConfirmed = false;
    private final String serverName;
    // Redis 信道（可选，由 AuthModule 注入）
    private CrossServerChannel redisChannel;
    // 跨服绑定查询：requestId -> 回调
    private final java.util.Map<String, java.util.function.Consumer<String>> bindingCallbacks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SpigotBridge(XingtuBot plugin) {
        instance = this;
        this.plugin = plugin;
        this.serverName = plugin.getConfig().getString("server-name", "server");

        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CH);
        messenger.registerIncomingPluginChannel(plugin, CH, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new PlayerLockListener(plugin, lockState, awaitingQQ), plugin);
        // 锁定期的持续提示（三阶段不同标题）由【代理大脑】统一驱动（它知道绑定状态），子服不再本地循环。
    }

    public static SpigotBridge getInstance() { return instance; }

    /** 注入 Redis 信道（AuthModule 根据 config 决定是否创建）。 */
    public void setRedisChannel(CrossServerChannel redisChannel) {
        this.redisChannel = redisChannel;
        // 注册 Redis 消息处理（复用 PluginMessage 同一套逻辑）
        redisChannel.setMessageHandler((fromServer, data) -> {
            BridgeCodec.Decoded msg = BridgeCodec.decode(data);
            if (msg != null) handleMessage(msg, null);
        });
    }

    // ==================== 上报给大脑 ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        final String name = p.getName();
        lockState.lock(name); // 进服先锁，等大脑(Velocity)指示
        // 进服判定已交由 Velocity 原生 ServerPostConnectEvent 驱动，子服不再上报 PLAYER_JOIN。
        // 仅保留一个延迟握手用于「代理大脑是否在线」诊断：刚进服时 proxy↔backend 通道常未就绪，
        // 立即发会丢包，故延迟 2s 再发；10s 内仍无应答才告警。
        if (!brainConfirmed) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player pl = Bukkit.getPlayerExact(name);
                if (pl != null && pl.isOnline() && !brainConfirmed) {
                    send(pl, BridgeCodec.encode(CrossServerProtocol.Type.WHO_IS_BOSS,
                            plugin.getServer().getName()));
                }
            }, 40L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!brainConfirmed) {
                    plugin.getLogger().warning("⚠️ 白名单手脚模式：未收到代理(大脑)应答。"
                            + "请确认 Velocity 已装本插件且 server-role 非 off、whitelist-enable=true。");
                }
            }, 200L);
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        if (!awaitingQQ.contains(name)) return;

        event.setCancelled(true);
        String qq = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () ->
                send(p, BridgeCodec.encode(CrossServerProtocol.Type.DECLARE_QQ, name, qq)));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        awaitingQQ.remove(p.getName());
        lockState.clear(p.getName());
        send(p, BridgeCodec.encode(CrossServerProtocol.Type.PLAYER_QUIT, p.getName()));
    }

    // ==================== 执行大脑下发 ====================

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!CH.equals(channel)) return;
        BridgeCodec.Decoded msg = BridgeCodec.decode(data);
        if (msg == null) return;
        handleMessage(msg, player);
    }

    /** 统一消息处理（PluginMessage 和 Redis 共用）。carrier 可为 null（Redis 通道）。 */
    private void handleMessage(BridgeCodec.Decoded msg, Player carrier) {
        switch (msg.type) {
            case I_AM_BOSS:
                if (!brainConfirmed) {
                    brainConfirmed = true;
                    plugin.getLogger().info("✅ 白名单手脚模式：已连上代理大脑");
                }
                break;
            case NEED_QQ:
                if (msg.field(0) != null) awaitingQQ.add(msg.field(0));
                break;
            case CLEAR_QQ:
                if (msg.field(0) != null) awaitingQQ.remove(msg.field(0));
                break;
            case DO_REGISTER:
                if (msg.field(0) != null) {
                    lockState.unlock(msg.field(0));
                    final String regName = msg.field(0);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayerExact(regName);
                        if (p != null) JoinQrMap.cleanup(p);
                    });
                    msgPlayer(msg.field(0), LockMessages.get("bound"));
                }
                break;
            case DO_LOGIN:
                if (msg.field(0) != null) {
                    lockState.unlock(msg.field(0));
                    final String loginName = msg.field(0);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayerExact(loginName);
                        if (p != null) JoinQrMap.cleanup(p);
                    });
                    msgPlayer(msg.field(0), LockMessages.unlocked());
                }
                break;
            case MSG_PLAYER:
                msgPlayer(msg.field(0), msg.field(1));
                break;
            case PAPI_RESOLVE: {
                String requestId = msg.field(0);
                String playerName = msg.field(1);
                String text = msg.field(2);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String resolved = PapiResolver.resolve(playerName, text);
                    sendResponse(BridgeCodec.encode(
                            CrossServerProtocol.Type.PAPI_RESULT, requestId, resolved));
                });
                break;
            }
            case BINDING_RESULT: {
                String requestId = msg.field(0);
                String resultPlayer = msg.field(1);
                java.util.function.Consumer<String> cb =
                        (requestId == null) ? null : bindingCallbacks.remove(requestId);
                if (cb != null) cb.accept(resultPlayer != null ? resultPlayer : "");
                break;
            }
            case DO_CONSOLE: {
                String target = msg.field(0);
                String requestId = msg.field(1);
                String command = msg.field(2);
                if (target == null || command == null) break;
                if (!target.equals(serverName) && !"all".equalsIgnoreCase(target)) break;
                org.windy.xingtubot.bukkit.module.console.ConsoleExecutor.execute(plugin, command, output ->
                        sendResponse(BridgeCodec.encode(CrossServerProtocol.Type.CONSOLE_RESULT,
                                requestId, serverName, output)));
                break;
            }
            default:
                break;
        }
    }

    private void msgPlayer(String player, String message) {
        if (player == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    /** 把消息发往代理大脑。有 Redis 优先走 Redis，否则用在线玩家连接发 PluginMessage。 */
    private void send(Player carrier, byte[] data) {
        // 有 Redis 信道：优先走 Redis（无需在线玩家当载体；也避免与 PluginMessage 双发导致大脑重复处理）。
        if (redisChannel != null) {
            redisChannel.broadcast(data);
            return;
        }
        // 回退 PluginMessage（需要在线玩家作为载体）。
        if (carrier != null && carrier.isOnline()) {
            carrier.sendPluginMessage(plugin, CH, data);
        }
    }

    /** 发送响应消息（PluginMessage + Redis 双发）。 */
    private void sendResponse(byte[] data) {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        send(carrier, data);
    }

    /**
     * 向 Velocity 查询某 openid 绑定了哪个玩家。
     * 结果通过回调异步返回（空字符串=未绑定）。
     * 需要有在线玩家才能发插件消息。
     */
    public void queryBindingByOpenid(String openid, java.util.function.Consumer<String> onResult) {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        // 无在线玩家且无 Redis 信道时才发不出去；有 Redis 时不需要载体玩家。
        if (carrier == null && redisChannel == null) {
            onResult.accept("");
            return;
        }
        String requestId = java.util.UUID.randomUUID().toString();
        bindingCallbacks.put(requestId, onResult);
        send(carrier, BridgeCodec.encode(CrossServerProtocol.Type.QUERY_BINDING_BY_OPENID, requestId, openid));
        // 3 秒超时
        Thread t = new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            java.util.function.Consumer<String> cb = bindingCallbacks.remove(requestId);
            if (cb != null) cb.accept("");
        });
        t.setDaemon(true);
        t.start();
    }
}
