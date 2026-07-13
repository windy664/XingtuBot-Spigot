package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.whitelist.AbstractDirectAuthAdapter;
import org.windy.xingtubot.common.whitelist.LockMessages;

/**
 * Bukkit 端直通 {@link AuthAdapter}。
 */
public class BukkitDirectAuthAdapter extends AbstractDirectAuthAdapter {

    private final BukkitPlayerLock lock;

    public BukkitDirectAuthAdapter(BukkitPlayerLock lock, BukkitPlayerOps ops) {
        super(ops);
        this.lock = lock;
    }

    @Override
    public void register(String player) {
        lock.unlock(player);
        Object p = ops.getPlayer(player);
        if (p != null && ops.isOnline(p)) {
            JoinQrMap.cleanup(Bukkit.getPlayerExact(player));
            ops.sendMessage(p, LockMessages.get("bound"));
        }
    }

    @Override
    public void login(String player) {
        lock.unlock(player);
        Object p = ops.getPlayer(player);
        if (p != null && ops.isOnline(p)) {
            JoinQrMap.cleanup(Bukkit.getPlayerExact(player));
            ops.sendMessage(p, LockMessages.unlocked());
        }
    }

    @Override
    public void titlePlayer(String player, String mainTitle, String subTitle) {
        org.windy.xingtubot.common.whitelist.LockTitle.send(
                ops.getPlayer(player), mainTitle + (subTitle != null ? "\n" + subTitle : ""));
    }
}
