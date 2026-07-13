package org.windy.xingtubot.common.whitelist;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;

import java.util.ArrayList;
import java.util.List;

/**
 * 锁定期背包遮罩：往客户端发"整窗覆盖"的 {@link WrapperPlayServerWindowItems} 让玩家自身背包（窗口 0）显示为空。
 *
 * <p>用「发覆盖包」而非「取消 clientbound 包」——后者在 Velocity 上会把玩家踢下线（packetevents 代理端取消
 * PacketSendEvent 的已知坑）。锁定期玩家改动不了背包（serverbound 交互包已被 {@link LockPacketListener} 拦），
 * 故一次覆盖后保持为空。
 *
 * <p>不碰后端真实背包：只发客户端显示层的覆盖包。真实内容由 {@link LockPacketListener#onPacketSend} 读取缓存，
 * 解锁时经 {@link #restore} 原样发回，玩家背包即刻恢复。
 */
public final class InventoryMask {

    /** 玩家背包窗口（windowId=0）槽位数：0 输出 / 1-4 合成 / 5-8 盔甲 / 9-35 主仓 / 36-44 快捷栏 / 45 副手 = 46。 */
    public static final int PLAYER_INVENTORY_SLOTS = 46;

    private InventoryMask() {
    }

    /** 清空玩家自身背包显示（覆盖 46 格 + 光标为空）。不影响后端真实背包。 */
    public static void clear(Object player) {
        if (player == null || !QrMapSender.available()) return;
        try {
            List<ItemStack> empty = new ArrayList<>(PLAYER_INVENTORY_SLOTS);
            for (int i = 0; i < PLAYER_INVENTORY_SLOTS; i++) empty.add(ItemStack.EMPTY);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerWindowItems(0, 0, empty, ItemStack.EMPTY));
        } catch (Throwable ignored) {
        }
    }

    /** 清空玩家背包显示（onPacketSend 内直接用 packetevents User 发，无需平台 player 对象）。 */
    public static void clear(User user) {
        if (user == null) return;
        try {
            List<ItemStack> empty = new ArrayList<>(PLAYER_INVENTORY_SLOTS);
            for (int i = 0; i < PLAYER_INVENTORY_SLOTS; i++) empty.add(ItemStack.EMPTY);
            user.sendPacket(new WrapperPlayServerWindowItems(0, 0, empty, ItemStack.EMPTY));
        } catch (Throwable ignored) {
        }
    }

    /** 解锁时把捕获的真实背包重新发回客户端。 */
    public static void restore(Object player, List<ItemStack> items, ItemStack carried) {
        if (player == null || items == null || items.isEmpty() || !QrMapSender.available()) return;
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerWindowItems(0, 0, items, carried == null ? ItemStack.EMPTY : carried));
        } catch (Throwable ignored) {
        }
    }

    /** 一组物品是否全空（用于 onPacketSend 判断是否为我们自己发的覆盖包，防自触发死循环）。 */
    public static boolean allEmpty(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return true;
        for (ItemStack it : items) {
            if (it != null && !it.isEmpty()) return false;
        }
        return true;
    }
}
