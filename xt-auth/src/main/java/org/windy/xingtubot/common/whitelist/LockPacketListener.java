package org.windy.xingtubot.common.whitelist;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;

/**
 * 三端通用的 packetevents 包级登录锁拦截器（Bukkit/Velocity/BungeeCord 共用一份）。
 *
 * <p>基于 packetevents {@link User}（自带 {@code getName()} + {@code sendPacket()}），只依赖
 * {@link LockTarget} 抽象，不碰任何平台 player 类型——原三端逐字相同的三个监听器合并至此。
 *
 * <p>拦截策略：
 * <ul>
 *   <li><b>白名单放行</b>：KEEP_ALIVE / TELEPORT_CONFIRM / CLIENT_SETTINGS / CHUNK_BATCH_ACK / PLAYER_ROTATION
 *       （纯转头放行——锁定期玩家能自由抬头看手里的加群地图）</li>
 *   <li><b>聊天</b>：awaitingQQ 阶段读内容交给 {@link LockTarget#handleChatInput}，否则吞掉</li>
 *   <li><b>命令</b>：全部拦截</li>
 *   <li><b>移动</b>：首个 Position 包捕获锁定坐标，后续拦截 + 拉回原地
 *       （位置绝对、视角相对 0x18 = 走不动但视角自由）</li>
 *   <li><b>其余全部拦截</b></li>
 * </ul>
 *
 * <p>server→client 包仅在 {@code onPacketSend} 处理背包遮罩（WINDOW_ITEMS 窗口 0 抹空 + 缓存供解锁恢复），
 * 其余（区块/实体/世界数据）全放行正常下发。
 */
public class LockPacketListener extends PacketListenerAbstract {

    /**
     * 回拉 teleport 的最小间隔(ms)。每个回拉 teleport 都会引来客户端一个 TELEPORT_CONFIRM(放行转发后端)，
     * 若持续行走时每 tick 一发(~20/秒)，serverbound 确认包会冲爆后端的 disconnect.exceeded_packet_rate。
     * 限流到 ~4/秒(250ms)，足够把玩家钉在原地，又远低于速率上限。
     */
    private static final long CORRECTION_MIN_INTERVAL_MS = 250;

    private final LockTarget target;
    /** per-player 上次回拉时间戳，用于限流(键=小写玩家名)。 */
    private final java.util.Map<String, Long> lastCorrection = new java.util.concurrent.ConcurrentHashMap<>();

    public LockPacketListener(LockTarget target) {
        super(PacketListenerPriority.HIGHEST);
        this.target = target;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        String name = user.getName();
        if (name == null || !target.isLocked(name)) return;

        PacketTypeCommon type = event.getPacketType();

        if (isWhitelisted(type)) return;

        if (type == PacketType.Play.Client.CHAT_MESSAGE) {
            event.setCancelled(true); // 拦截，不让到子服
            if (target.isAwaitingQQ(name)) {
                try {
                    String message = new WrapperPlayClientChatMessage(event).getMessage();
                    target.handleChatInput(name, message);
                } catch (Exception ignored) {
                    // 读取失败静默忽略（版本不兼容或包格式变化）
                }
            }
            return;
        }
        if (type == PacketType.Play.Client.CHAT_COMMAND) {
            event.setCancelled(true); // 锁定玩家不能执行任何命令
            return;
        }

        if (isMovement(type)) {
            handleMovement(event, user, name, type);
            return;
        }

        event.setCancelled(true);
    }

    /**
     * server→client 包：只处理玩家自身背包（WINDOW_ITEMS，窗口 0），其余全放行。
     * <p>锁定期把背包显示抹空：读后端下发的真实背包→首次缓存供解锁恢复→紧跟一个清空覆盖包。
     * <b>绝不取消/改写后端包</b>（代理端取消 clientbound 会把玩家踢下线），只额外发我们自己的覆盖包。
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.WINDOW_ITEMS) return;
        User user = event.getUser();
        String name = user.getName();
        if (name == null || !target.isLocked(name)) return;

        // 整段兜 try：读 WindowItems wrapper 若在某版本失败也绝不能抛进 netty 管线
        // （否则触发 Velocity "internal server connection error" 踢人）；失败就原样放行。
        try {
            WrapperPlayServerWindowItems w = new WrapperPlayServerWindowItems(event);
            if (w.getWindowId() != 0) return;                  // 只管玩家自身背包
            if (InventoryMask.allEmpty(w.getItems())) return;  // 全空=我们自己发的覆盖包/本就空背包，忽略防死循环

            // 首次捕获真实背包供解锁恢复（锁期玩家改不了背包，首捕即准）
            if (!target.hasCapturedInventory(name)) {
                target.captureInventory(name, w.getItems(), w.getCarriedItem().orElse(ItemStack.EMPTY));
            }
            // 不动后端这个包，紧跟一个清空覆盖包把显示抹空
            InventoryMask.clear(user);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isWhitelisted(PacketTypeCommon type) {
        // 心跳 / 传送确认 / 客户端设置 / 区块批量确认——维持连接必须，放行到后端。
        // 注意：PLAYER_ROTATION 不放行——转头包若转发后端，走路+晃视角时会冲爆后端数据包速率限制被踢。
        return type == PacketType.Play.Client.KEEP_ALIVE
                || type == PacketType.Play.Client.TELEPORT_CONFIRM
                || type == PacketType.Play.Client.CLIENT_SETTINGS
                || type == PacketType.Play.Client.CHUNK_BATCH_ACK;
    }

    private static boolean isMovement(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING;
    }

    private void handleMovement(PacketReceiveEvent event, User user, String name, PacketTypeCommon type) {
        event.setCancelled(true);

        // 只有"真正带位置"的包才回拉。PLAYER_FLYING(原地心跳,站着也每 tick 发)与 PLAYER_ROTATION(纯转头)
        // 不改变位置——对它们回拉会让站着不动/转头也每 tick 发 teleport → 每个 teleport 引来一个
        // TELEPORT_CONFIRM(放行转发后端)→ serverbound 确认包 20/秒冲爆 exceeded_packet_rate。
        // 这两类只取消、不回拉。转头包始终不放行转发后端(仅取消)，兼顾"自由转头看二维码"且不冲后端。
        boolean positional = type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
        if (!positional) return;

        tryCapturePosition(event, name, type); // 首个 Position 包捕获锁定坐标（只取一次）

        // 限流:持续行走时客户端每 tick 发位置包,最多每 CORRECTION_MIN_INTERVAL_MS 回拉一次。
        String key = name.toLowerCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = lastCorrection.get(key);
        if (last != null && now - last < CORRECTION_MIN_INTERVAL_MS) return;

        // 回拉:位置绝对(锁死原地) + 视角相对(flags 0x18 = yaw/pitch 相对传 0 = 保持当前朝向)
        // → 走不动但能自由转头/举着加群二维码地图看。
        LockPosition data = target.getLockData(name);
        if (data != null && data.hasPosition()) {
            lastCorrection.put(key, now);
            WrapperPlayServerPlayerPositionAndLook pos = new WrapperPlayServerPlayerPositionAndLook(
                    data.getX(), data.getY(), data.getZ(),
                    0f, 0f, (byte) 0x18, 0, false);
            user.sendPacket(pos);
        }
    }

    /**
     * 从首个 Position 包捕获锁定坐标（只取一次）。
     * <p>直接从 ByteBuf 读 payload（x/y/z 三个 double，POSITION_AND_ROTATION 再带 yaw/pitch）；
     * packetevents 已读掉 packetId，游标在 payload 起始。
     */
    private void tryCapturePosition(PacketReceiveEvent event, String name, PacketTypeCommon type) {
        LockPosition existing = target.getLockData(name);
        if (existing != null && existing.hasPosition()) return; // 已捕获过

        try {
            Object buf = event.getByteBuf();
            ByteBufHelper.markReaderIndex(buf);

            double x = ByteBufHelper.readDouble(buf);
            double y = ByteBufHelper.readDouble(buf);
            double z = ByteBufHelper.readDouble(buf);

            float yaw = 0, pitch = 0;
            if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                yaw = ByteBufHelper.readFloat(buf);
                pitch = ByteBufHelper.readFloat(buf);
            }

            ByteBufHelper.resetReaderIndex(buf);
            target.capturePosition(name, x, y, z, yaw, pitch);
        } catch (Exception e) {
            try {
                ByteBufHelper.resetReaderIndex(event.getByteBuf());
            } catch (Exception ignored) {
            }
            target.capturePosition(name, 0, 64, 0, 0, 0); // 读取失败回退默认坐标
        }
    }
}
