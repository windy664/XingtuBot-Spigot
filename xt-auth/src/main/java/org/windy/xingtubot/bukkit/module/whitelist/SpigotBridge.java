package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.windy.xingtubot.bukkit.XingtuBot;
import org.windy.xingtubot.common.bridge.BridgeCodec;
import org.windy.xingtubot.common.bridge.CrossServerChannel;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;

/**
 * 白名单「手脚」残留职责（slave）。自研锁已全量迁移到代理端 packetevents 自闭环
 * （进服判定/锁定/解锁都在代理大脑），本类只保留两件事：
 * <ul>
 *   <li>进服延迟握手（WHO_IS_BOSS）：诊断「代理大脑是否在线」；</li>
 *   <li>接收大脑的 {@code I_AM_BOSS}，确认大脑在线（brainConfirmed）并记录代理端注册名。</li>
 * </ul>
 *
 * <p>核心自有跨服消息（DO_CONSOLE / PAPI_RESOLVE / SERVER_REGISTRY 等）由核心
 * {@code XingtuBot} 自己的入站监听处理，不再经过本类。
 */
public class SpigotBridge implements Listener, PluginMessageListener {

    private static final String CH = CrossServerProtocol.CHANNEL;
    private static SpigotBridge instance;

    private final XingtuBot plugin;
    private volatile boolean brainConfirmed = false;
    // Redis 信道（可选，由 AuthModule 注入）
    private CrossServerChannel redisChannel;

    public SpigotBridge(XingtuBot plugin) {
        instance = this;
        this.plugin = plugin;

        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CH);
        messenger.registerIncomingPluginChannel(plugin, CH, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static SpigotBridge getInstance() { return instance; }

    /** 注入 Redis 信道（AuthModule 根据 config 决定是否创建）。 */
    public void setRedisChannel(CrossServerChannel redisChannel) {
        this.redisChannel = redisChannel;
        plugin.getLogger().info("[Auth] SpigotBridge 已绑定 Redis 信道（auth 消息处理器）");
        // 追加 auth 消息处理（不覆盖 core 已注册的 DO_CONSOLE/WHO_IS_BOSS/PAPI_RESOLVE 等处理器）
        redisChannel.addMessageHandler((fromServer, data) -> {
            BridgeCodec.Decoded msg = BridgeCodec.decode(data);
            if (msg == null) return;
            if (msg.type == CrossServerProtocol.Type.I_AM_BOSS) {
                handleMessage(msg);
            }
        });
    }

    // ==================== 握手诊断 ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 进服判定已交由代理原生 ServerPostConnectEvent 驱动，子服不再上报 PLAYER_JOIN。
        // 仅保留一个延迟握手用于「代理大脑是否在线」诊断：刚进服时 proxy↔backend 通道常未就绪，
        // 立即发会丢包，故延迟 2s 再发；10s 内仍无应答才告警。
        final String name = event.getPlayer().getName();
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
                            + "请确认代理已装本插件且 whitelist-enable=true。");
                }
            }, 200L);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!CH.equals(channel)) return;
        BridgeCodec.Decoded msg = BridgeCodec.decode(data);
        if (msg == null) return;
        handleMessage(msg);
    }

    /** auth 消息处理（PluginMessage 与 Redis 共用）。 */
    private void handleMessage(BridgeCodec.Decoded msg) {
        if (msg.type == CrossServerProtocol.Type.I_AM_BOSS) {
            if (!brainConfirmed) {
                brainConfirmed = true;
                plugin.getLogger().info("✅ 白名单手脚模式：已连上代理大脑");
            }
            // 代理端回复的子服注册名（由 Velocity/BungeeCord 在 I_AM_BOSS 中携带）
            String proxyName = msg.field(0);
            if (proxyName != null && !proxyName.isEmpty()) {
                org.windy.xingtubot.common.runtime.BotRuntimeState.setProxyServerName(proxyName);
                plugin.getLogger().info("✅ 代理端注册名: " + proxyName);
            }
        }
    }

    /** 把消息发往代理大脑。有 Redis 优先走 Redis，否则用在线玩家连接发 PluginMessage。 */
    private void send(Player carrier, byte[] data) {
        // 有 Redis 信道：优先走 Redis（无需在线玩家当载体）。
        if (redisChannel != null) {
            redisChannel.broadcast(data);
            return;
        }
        // 回退 PluginMessage（需要在线玩家作为载体）。
        if (carrier != null && carrier.isOnline()) {
            carrier.sendPluginMessage(plugin, CH, data);
        }
    }
}
