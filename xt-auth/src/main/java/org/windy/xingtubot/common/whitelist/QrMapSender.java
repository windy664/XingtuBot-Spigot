package org.windy.xingtubot.common.whitelist;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;

import java.util.Arrays;
import java.util.Collections;

/**
 * 三端通用的「加群二维码地图」发包器：用 PacketEvents 把二维码地图直发给客户端，
 * 并强制玩家手持，扫码即可加群。不写入真实背包、不碰子服。
 *
 * <p>平台无关：PacketEvents 的 {@code getPlayerManager().sendPacket(Object, wrapper)} 接受任意平台的
 * player 对象（Bukkit {@code Player} / Velocity {@code Player} / BungeeCord {@code ProxiedPlayer}），
 * 故 Bukkit / Velocity / BungeeCord 三端共用本类，调用方各自把自己的 player 以 {@code Object} 传入。
 *
 * <p>PacketEvents 已内置进本插件 jar 并在三端主类启动时自初始化；本类经 {@link #available()}
 * 守卫懒加载，运行期不可用时返回 false 由调用方兜底。
 */
public final class QrMapSender {

    /** 专用地图 id：取一个极端值避开子服真实地图（真实地图从 0 递增）。 */
    private static final int MAP_ID = 2_000_000_000;
    /** 地图调色板色号：白 / 黑（Spigot MapPalette 经典值，跨版本稳定）。 */
    private static final byte WHITE = 34;
    private static final byte BLACK = 119;

    private QrMapSender() {
    }

    /** PacketEvents 是否在场且已初始化（守卫，避免 NoClassDefFoundError / NPE）。 */
    public static boolean available() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            return PacketEvents.getAPI() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 发地图数据 + SetSlot（filled_map 到快捷栏第一格）+ 强制手持，让客户端把二维码显示在手上。
     *
     * @param player 平台原生 player 对象（PacketEvents 按其客户端版本自动序列化）
     * @return 是否成功发出
     */
    public static boolean send(Object player, boolean[][] qr) {
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

            // 强制玩家手持这把地图（切到快捷栏第 0 格），否则玩家可能选着别的格子、根本看不见二维码
            WrapperPlayServerHeldItemChange held = new WrapperPlayServerHeldItemChange(0);

            PacketEvents.getAPI().getPlayerManager().sendPacket(player, map);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, slot);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, held);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 二维码矩阵 → 128×128 地图色字节：底白、黑点画黑、居中放大。 */
    private static byte[] render(boolean[][] qr) {
        byte[] data = new byte[128 * 128];
        Arrays.fill(data, WHITE);
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
