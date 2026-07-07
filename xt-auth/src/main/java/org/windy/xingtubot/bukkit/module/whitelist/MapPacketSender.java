package org.windy.xingtubot.bukkit.module.whitelist;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

/**
 * 用 ProtocolLib 把地图「纯发包」给客户端：物品只存在于客户端某个槽位，<b>不写入真实背包</b>。
 *
 * <p>本类直接 import ProtocolLib API——必须由 {@link JoinQrMap} 在确认 ProtocolLib 已安装后才调用，
 * 否则类加载会 NoClassDefFoundError。JVM 懒加载本类，调用前 {@code Class.forName} 守卫即可。
 *
 * <p>跨版本：SetSlot 包 1.17.1+ 多了 stateId 字段——交给 ProtocolLib 的 {@link StructureModifier}
 * 按字段数量自适应，不手写 NMS。
 */
final class MapPacketSender {

    private MapPacketSender() {
    }

    /** ProtocolLib 是否在场（守卫，避免 NoClassDefFoundError）。 */
    static boolean available() {
        try {
            Class.forName("com.comphenix.protocol.ProtocolLibrary");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 纯发包：先发地图像素数据，再用 SetSlot 把地图物品塞到客户端的当前手持槽（不动服务端背包）。
     *
     * @param heldSlot 玩家当前手持快捷栏槽位（0-8）
     * @return 是否成功发包
     */
    static boolean sendVirtual(Player player, MapView view, ItemStack mapItem, int heldSlot) {
        try {
            // 1) 地图像素数据包（Bukkit 稳定 API，触发渲染器生成 Map Data）
            player.sendMap(view);

            // 2) SetSlot 包：windowId=0（玩家自身背包），slot=容器槽（快捷栏 0-8 → 36-44）
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketContainer packet = pm.createPacket(PacketType.Play.Server.SET_SLOT);
            int containerSlot = 36 + Math.max(0, Math.min(8, heldSlot));

            StructureModifier<Integer> ints = packet.getIntegers();
            if (ints.size() >= 3) {
                // 1.17.1+：windowId, stateId, slot
                ints.write(0, 0);
                ints.write(1, 0);
                ints.write(2, containerSlot);
            } else {
                // ≤1.16：windowId, slot
                ints.write(0, 0);
                ints.write(1, containerSlot);
            }
            packet.getItemModifier().write(0, mapItem);

            pm.sendServerPacket(player, packet);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
