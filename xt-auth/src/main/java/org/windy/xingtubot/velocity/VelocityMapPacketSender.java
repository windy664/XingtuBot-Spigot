package org.windy.xingtubot.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.velocitypowered.api.proxy.Player;

import java.util.Collections;

/**
 * 用 PacketEvents 在 Velocity 代理端把地图「直发」给客户端（不反射、不碰子服背包）。
 *
 * <p>本类直接 import PacketEvents——必须由 {@link VelocityJoinQrMap} 在 {@link #available()}
 * 确认 PacketEvents 已安装后才调用，JVM 懒加载保证没装时不会 NoClassDefFoundError。
 *
 * <p>跨版本：{@link WrapperPlayServerSetSlot} 的 stateId、地图包序列化都交给 PacketEvents 兜底。
 */
final class VelocityMapPacketSender {

    /** 专用地图 id：取一个极端值避开子服真实地图（真实地图从 0 递增）。 */
    private static final int MAP_ID = 2_000_000_000;
    /** 地图调色板色号：白 / 黑（Spigot MapPalette 经典值，跨版本稳定）。 */
    private static final byte WHITE = 34;
    private static final byte BLACK = 119;

    private VelocityMapPacketSender() {
    }

    /** PacketEvents 是否在场（守卫，避免 NoClassDefFoundError）。 */
    static boolean available() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            return PacketEvents.getAPI() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 发地图数据 + SetSlot（filled_map 到快捷栏第一格），让客户端显示二维码。 */
    static boolean send(Player player, boolean[][] qr) {
        try {
            byte[] data = render(qr);

            WrapperPlayServerMapData map = new WrapperPlayServerMapData(
                    MAP_ID, (byte) 0, false, true,
                    Collections.<WrapperPlayServerMapData.MapDecoration>emptyList(),
                    128, 128, 0, 0, data);

            // map id 在 ≤1.20.4 存 item NBT、≥1.20.5 改存数据组件(minecraft:map_id)。
            // 两者都设：PacketEvents 发包时按该玩家客户端版本序列化，自动二选一，跨版本通吃。
            ItemStack item = ItemStack.builder()
                    .type(ItemTypes.FILLED_MAP)
                    .amount(1)
                    .nbt("map", new NBTInt(MAP_ID))           // ≤1.20.4
                    .component(ComponentTypes.MAP_ID, MAP_ID) // ≥1.20.5
                    .build();
            // windowId=0 玩家自身背包，slot=36 快捷栏第一格，stateId 交给 PacketEvents
            WrapperPlayServerSetSlot slot = new WrapperPlayServerSetSlot(0, 0, 36, item);

            PacketEvents.getAPI().getPlayerManager().sendPacket(player, map);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, slot);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 二维码矩阵 → 128×128 地图色字节：底白、黑点画黑、居中放大。 */
    private static byte[] render(boolean[][] qr) {
        byte[] data = new byte[128 * 128];
        java.util.Arrays.fill(data, WHITE);
        if (qr == null || qr.length == 0) return data;

        int n = qr.length;
        int scale = Math.max(1, 128 / n);
        int size = n * scale;
        int off = (128 - size) / 2;

        for (int r = 0; r < n; r++) {
            boolean[] row = qr[r];
            for (int c = 0; c < row.length; c++) {
                if (!row[c]) continue;
                int px = off + c * scale;
                int py = off + r * scale;
                for (int dx = 0; dx < scale; dx++) {
                    for (int dy = 0; dy < scale; dy++) {
                        int x = px + dx, y = py + dy;
                        if (x >= 0 && x < 128 && y >= 0 && y < 128) {
                            data[y * 128 + x] = BLACK;
                        }
                    }
                }
            }
        }
        return data;
    }
}
