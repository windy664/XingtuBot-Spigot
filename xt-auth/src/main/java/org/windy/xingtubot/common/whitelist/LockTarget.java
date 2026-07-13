package org.windy.xingtubot.common.whitelist;

/**
 * 三端登录锁的共享抽象，供 {@link LockPacketListener} 驱动。
 * <p>Bukkit/Velocity/BungeeCord 各自的 PlayerLock 实现之，从而三端共用同一份包拦截器。
 */
public interface LockTarget {

    /** 该玩家是否处于锁定态。 */
    boolean isLocked(String name);

    /** 该玩家是否还在等待输入 QQ 号（未声明 QQ）。 */
    boolean isAwaitingQQ(String name);

    /**
     * 处理玩家在聊天框输入的内容（尝试解析为 QQ 号）。
     * <p>实现方内部按名字自行取平台 player 发送反馈——包拦截器不关心平台 player 类型。
     */
    void handleChatInput(String name, String message);

    /** 取锁定坐标（{@code null} = 尚未捕获）。 */
    LockPosition getLockData(String name);

    /**
     * 从首个 Position 包捕获锁定坐标（只取一次）。
     * @return true 表示首次捕获
     */
    boolean capturePosition(String name, double x, double y, double z, float yaw, float pitch);

    /** 该玩家的真实背包是否已被捕获（供解锁恢复）。 */
    boolean hasCapturedInventory(String name);

    /**
     * 缓存后端下发的真实背包（锁期显空、解锁时用它恢复）。锁期玩家改不了背包，故首次捕获即准确。
     * @param items   窗口 0 全部槽位的真实内容（packetevents ItemStack 列表）
     * @param carried 光标上的物品
     */
    void captureInventory(String name,
                          java.util.List<com.github.retrooper.packetevents.protocol.item.ItemStack> items,
                          com.github.retrooper.packetevents.protocol.item.ItemStack carried);
}
