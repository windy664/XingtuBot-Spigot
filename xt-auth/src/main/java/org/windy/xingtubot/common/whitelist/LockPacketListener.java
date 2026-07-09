package org.windy.xingtubot.common.whitelist;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;

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
 * <p>server→client 包不覆写 {@code onPacketSend} = 全放行，区块/实体/世界数据正常下发。
 */
public class LockPacketListener extends PacketListenerAbstract {

    private final LockTarget target;

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

    private static boolean isWhitelisted(PacketTypeCommon type) {
        return type == PacketType.Play.Client.KEEP_ALIVE
                || type == PacketType.Play.Client.TELEPORT_CONFIRM
                || type == PacketType.Play.Client.CLIENT_SETTINGS
                || type == PacketType.Play.Client.CHUNK_BATCH_ACK
                || type == PacketType.Play.Client.PLAYER_ROTATION; // 放行纯转头：锁定期可自由抬头看手里的地图
    }

    private static boolean isMovement(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING;
    }

    private void handleMovement(PacketReceiveEvent event, User user, String name, PacketTypeCommon type) {
        event.setCancelled(true);

        if (type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            tryCapturePosition(event, name, type);
        }

        LockPosition data = target.getLockData(name);
        if (data != null && data.hasPosition()) {
            // 位置绝对(拉回原地)、视角相对(yaw/pitch 传 0 = 保持玩家当前朝向)：
            // 玩家走不动，但能自由转头/抬头看手里的加群地图。flags 0x18 = yaw+pitch 相对。
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
