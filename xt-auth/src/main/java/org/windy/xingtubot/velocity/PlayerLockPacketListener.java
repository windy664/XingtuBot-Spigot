package org.windy.xingtubot.velocity;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * packetevents 包级拦截器：锁定玩家的包过滤。
 *
 * <p>拦截策略：
 * <ul>
 *   <li><b>白名单放行</b>：KEEP_ALIVE / TELEPORT_CONFIRM / CLIENT_SETTINGS / CHUNK_BATCH_ACK（维持连接必须）</li>
 *   <li><b>聊天处理</b>：未绑定玩家（awaitingQQ）读取聊天内容交给 VelocityPlayerLock 处理 QQ 输入；已绑定玩家拦截</li>
 *   <li><b>移动拦截</b>：首个 Position 包捕获锁定坐标，后续移动包拦截 + 发 Position 包拉回</li>
 *   <li><b>其他全部拦截</b>：交互/攻击/物品/方块操作等</li>
 * </ul>
 *
 * <p><b>服务端→客户端包（onPacketSend）全部放行</b>：区块/实体/世界数据正常下发，不崩客户端。
 */
public class PlayerLockPacketListener extends PacketListenerAbstract {

    private final ProxyServer proxy;
    private final VelocityPlayerLock lockManager;

    public PlayerLockPacketListener(ProxyServer proxy, VelocityPlayerLock lockManager) {
        super(PacketListenerPriority.HIGHEST);
        this.proxy = proxy;
        this.lockManager = lockManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        String name = user.getName();
        if (name == null || !lockManager.isLocked(name)) return;

        PacketTypeCommon type = event.getPacketType();

        // ===== 白名单：必须放行，否则连接断开 =====
        if (isWhitelisted(type)) return;

        // ===== 聊天处理 =====
        if (type == PacketType.Play.Client.CHAT_MESSAGE) {
            handleChat(event, name);
            return;
        }
        if (type == PacketType.Play.Client.CHAT_COMMAND) {
            // 命令全部拦截（锁定玩家不能执行任何命令）
            event.setCancelled(true);
            return;
        }

        // ===== 移动处理 =====
        if (isMovement(type)) {
            handleMovement(event, user, name, type);
            return;
        }

        // ===== 其他全部拦截 =====
        event.setCancelled(true);
    }

    /**
     * 服务端→客户端包全部放行（区块/实体/世界数据正常下发）。
     * <p>不覆写 onPacketSend = 默认不拦截任何 server→client 包。
     */

    // ===== 白名单判断 =====

    private static boolean isWhitelisted(PacketTypeCommon type) {
        // 心跳 / 传送确认 / 客户端设置 / 区块批量确认
        return type == PacketType.Play.Client.KEEP_ALIVE
                || type == PacketType.Play.Client.TELEPORT_CONFIRM
                || type == PacketType.Play.Client.CLIENT_SETTINGS
                || type == PacketType.Play.Client.CHUNK_BATCH_ACK;
    }

    // ===== 聊天处理 =====

    private void handleChat(PacketReceiveEvent event, String name) {
        event.setCancelled(true); // 拦截，不让到子服

        if (!lockManager.isAwaitingQQ(name)) {
            // 已绑定或其他状态：聊天已被拦截，不需要额外处理（玩家应该在群里发「登录」）
            return;
        }

        // 未绑定玩家：读取聊天内容，交给 VelocityPlayerLock 处理 QQ 输入
        try {
            WrapperPlayClientChatMessage wrapper = new WrapperPlayClientChatMessage(event);
            String message = wrapper.getMessage();
            Player player = proxy.getPlayer(name).orElse(null);
            if (player != null) {
                lockManager.handleChatInput(player, name, message);
            }
        } catch (Exception e) {
            // 读取失败静默忽略（可能是版本不兼容或包格式变化）
        }
    }

    // ===== 移动处理 =====

    private static boolean isMovement(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING;
    }

    private void handleMovement(PacketReceiveEvent event, User user, String name,
                                PacketTypeCommon type) {
        event.setCancelled(true);

        // 尝试从 Position 包读取坐标并捕获（只取一次）
        if (type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            tryCapturePosition(event, name, type);
        }

        // 所有移动包：拉回锁定位置
        VelocityPlayerLock.LockData data = lockManager.getLockData(name);
        if (data != null && data.hasPosition()) {
            sendLockPosition(user, data);
        }
    }

    /**
     * 从首个 Position 包捕获锁定坐标。
     * <p>直接从 ByteBuf 读取坐标（客户端 Position 包格式：x, y, z 三个 double）。
     * packetevents 框架已读取 packetId VarInt，ByteBuf 游标在 payload 起始位置。
     */
    private void tryCapturePosition(PacketReceiveEvent event, String name, PacketTypeCommon type) {
        VelocityPlayerLock.LockData existing = lockManager.getLockData(name);
        if (existing != null && existing.hasPosition()) return; // 已经捕获过

        try {
            Object buf = event.getByteBuf();
            com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.markReaderIndex(buf);

            double x = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readDouble(buf);
            double y = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readDouble(buf);
            double z = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readDouble(buf);

            // 读 yaw/pitch（仅 POSITION_AND_ROTATION 包有）
            float yaw = 0, pitch = 0;
            if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                yaw = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readFloat(buf);
                pitch = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readFloat(buf);
            }

            com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.resetReaderIndex(buf);
            lockManager.capturePosition(name, x, y, z, yaw, pitch);
        } catch (Exception e) {
            // 读取失败，回退到默认坐标
            try {
                com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.resetReaderIndex(
                        event.getByteBuf());
            } catch (Exception ignored) {}
            lockManager.capturePosition(name, 0, 64, 0, 0, 0);
        }
    }

    /**
     * 发送 Position 包把玩家拉回锁定位置。
     * <p>使用 packetevents 的 wrapper 构造 Position+Look 包，通过 User.sendPacket 发送。
     * flags=0 表示所有坐标都是绝对值（非相对）。
     */
    private static void sendLockPosition(User user, VelocityPlayerLock.LockData data) {
        WrapperPlayServerPlayerPositionAndLook pos = new WrapperPlayServerPlayerPositionAndLook(
                data.getX(), data.getY(), data.getZ(),
                data.getYaw(), data.getPitch(),
                /* flags */ (byte) 0, /* teleportId */ 0, /* dismountVehicle */ false);
        user.sendPacket(pos);
    }
}
