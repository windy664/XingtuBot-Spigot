package org.windy.xingtubot.bukkit.module.whitelist;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Bukkit 端 packetevents 包级拦截器。
 * <p>独立模式（无代理）时使用。与 {@code PlayerLockPacketListener}（Velocity 版）功能对等。
 */
public class BukkitPacketListener extends PacketListenerAbstract {

    private final BukkitPlayerLock lockManager;

    public BukkitPacketListener(BukkitPlayerLock lockManager) {
        super(PacketListenerPriority.HIGHEST);
        this.lockManager = lockManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        String name = user.getName();
        if (name == null || !lockManager.isLocked(name)) return;

        PacketTypeCommon type = event.getPacketType();

        if (isWhitelisted(type)) return;

        if (type == PacketType.Play.Client.CHAT_MESSAGE) {
            handleChat(event, name);
            return;
        }
        if (type == PacketType.Play.Client.CHAT_COMMAND) {
            event.setCancelled(true);
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
                || type == PacketType.Play.Client.CHUNK_BATCH_ACK;
    }

    private void handleChat(PacketReceiveEvent event, String name) {
        event.setCancelled(true);
        if (!lockManager.isAwaitingQQ(name)) return;
        try {
            WrapperPlayClientChatMessage wrapper = new WrapperPlayClientChatMessage(event);
            String message = wrapper.getMessage();
            Player player = Bukkit.getPlayerExact(name);
            if (player != null && player.isOnline()) {
                lockManager.handleChatInput(player, name, message);
            }
        } catch (Exception ignored) {}
    }

    private static boolean isMovement(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING;
    }

    private void handleMovement(PacketReceiveEvent event, User user, String name,
                                PacketTypeCommon type) {
        event.setCancelled(true);

        if (type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            tryCapturePosition(event, name, type);
        }

        BukkitPlayerLock.LockData data = lockManager.getLockData(name);
        if (data != null && data.hasPosition()) {
            sendLockPosition(user, data);
        }
    }

    private void tryCapturePosition(PacketReceiveEvent event, String name, PacketTypeCommon type) {
        BukkitPlayerLock.LockData existing = lockManager.getLockData(name);
        if (existing != null && existing.hasPosition()) return;

        try {
            Object buf = event.getByteBuf();
            com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.markReaderIndex(buf);
            double x = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readDouble(buf);
            double y = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readDouble(buf);
            double z = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readDouble(buf);
            float yaw = 0, pitch = 0;
            if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                yaw = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readFloat(buf);
                pitch = com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.readFloat(buf);
            }
            com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.resetReaderIndex(buf);
            lockManager.capturePosition(name, x, y, z, yaw, pitch);
        } catch (Exception e) {
            try { com.github.retrooper.packetevents.netty.buffer.ByteBufHelper.resetReaderIndex(event.getByteBuf()); }
            catch (Exception ignored) {}
            lockManager.capturePosition(name, 0, 64, 0, 0, 0);
        }
    }

    private static void sendLockPosition(User user, BukkitPlayerLock.LockData data) {
        WrapperPlayServerPlayerPositionAndLook pos = new WrapperPlayServerPlayerPositionAndLook(
                data.getX(), data.getY(), data.getZ(),
                data.getYaw(), data.getPitch(),
                (byte) 0, 0, false);
        user.sendPacket(pos);
    }
}
