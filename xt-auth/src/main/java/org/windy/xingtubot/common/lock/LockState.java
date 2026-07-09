package org.windy.xingtubot.common.lock;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家锁定状态（平台无关）：记录哪些玩家处于「未登录/未绑定」的锁定态。
 *
 * <p>自研登录的核心状态，取代 AuthMe 的已认证判断。Spigot 端据此拦截被锁玩家的所有操作；
 * 绑定成功或群里登录后调用 {@link #unlock} 解锁。
 */
public class LockState {

    private final Set<String> locked = ConcurrentHashMap.newKeySet();

    /** 标记玩家为锁定（进服未登录时）。 */
    public void lock(String player) {
        locked.add(player.toLowerCase());
    }

    /** 解锁（登录/绑定成功）。 */
    public void unlock(String player) {
        locked.remove(player.toLowerCase());
    }

    public boolean isLocked(String player) {
        return locked.contains(player.toLowerCase());
    }

    /** 玩家离线时清理。 */
    public void clear(String player) {
        locked.remove(player.toLowerCase());
    }
}
