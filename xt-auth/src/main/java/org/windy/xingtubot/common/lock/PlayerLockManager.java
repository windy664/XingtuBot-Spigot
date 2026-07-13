package org.windy.xingtubot.common.lock;

/**
 * 玩家登录锁管理器接口（平台无关）。
 *
 * <p>代理桥经此接口操作锁状态，不直接依赖 xt-auth 的实现类。
 * 具体实现（如 packetevents 包级拦截）由 xt-auth 注入到代理桥。
 */
public interface PlayerLockManager {

    void lock(String player);

    void unlock(String player);

    boolean isLocked(String player);

    void onDisconnect(String player);
}
