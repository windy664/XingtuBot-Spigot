package org.windy.xingtubot.common.lock;

/**
 * 玩家登录锁管理器接口（平台无关）。
 *
 * <p>代理桥（{@code VelocityBridge}）经此接口操作锁状态，不直接依赖 xt-auth 的实现类。
 * 具体实现（如 packetevents 包级拦截）由 xt-auth 注入到代理桥。
 *
 * @see org.windy.xingtubot.common.lock.LockState
 */
public interface PlayerLockManager {

    /** 锁定玩家（进服未登录时）。 */
    void lock(String player);

    /** 解锁玩家（登录/绑定成功）。 */
    void unlock(String player);

    /** 玩家是否处于锁定态。 */
    boolean isLocked(String player);

    /** 玩家离线时清理所有状态。 */
    void onDisconnect(String player);
}
