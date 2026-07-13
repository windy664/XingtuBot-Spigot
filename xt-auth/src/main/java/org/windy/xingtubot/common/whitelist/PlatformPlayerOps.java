package org.windy.xingtubot.common.whitelist;

/**
 * 平台玩家操作抽象。
 *
 * <p>{@link #getPlayer} / {@link #isOnline} / {@link #sendMessage} 由三端子类实现（各 10-20 行）。
 * 发包（bossbar/title/QR地图/锁定坐标）全走 packetevents，不需要本接口。
 */
public abstract class PlatformPlayerOps {

    /** 按名字取在线玩家（返回平台原生对象，null=不在线）。 */
    public abstract Object getPlayer(String name);

    /** 玩家是否在线。 */
    public abstract boolean isOnline(Object player);

    /** 给玩家发消息（纯文本，支持 § 颜色码）。 */
    public abstract void sendMessage(Object player, String message);

    /** 异步执行（默认直接执行，Bukkit 子类覆盖为主线程调度）。 */
    public void runAsync(Runnable task) { task.run(); }

    /** 主线程执行（默认直接执行，Bukkit 子类覆盖为主线程调度）。 */
    public void runOnMain(Runnable task) { task.run(); }
}
