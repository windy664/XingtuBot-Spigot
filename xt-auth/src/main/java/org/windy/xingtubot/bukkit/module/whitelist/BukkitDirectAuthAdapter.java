package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.binding.AuthAdapter;

/**
 * Bukkit 端直通 {@link AuthAdapter}：unlock 直接调 {@link BukkitPlayerLock}。
 * <p>替代旧的 {@link LockAuthAdapter}（它用 LockState，不走 packetevents）。
 */
public class BukkitDirectAuthAdapter implements AuthAdapter {

    private final Plugin plugin;
    private final BukkitPlayerLock lock;

    public BukkitDirectAuthAdapter(Plugin plugin, BukkitPlayerLock lock) {
        this.plugin = plugin;
        this.lock = lock;
    }

    @Override
    public boolean isOnline(String player) {
        Player p = Bukkit.getPlayerExact(player);
        return p != null && p.isOnline();
    }

    @Override
    public void register(String player) {
        lock.unlock(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            if (p != null && p.isOnline()) {
                p.sendMessage("§a✅ 已绑定，祝游戏愉快！");
            }
        });
    }

    @Override
    public void login(String player) {
        lock.unlock(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            if (p != null && p.isOnline()) {
                p.sendMessage("§a✅ 已登录，祝游戏愉快！");
            }
        });
    }

    @Override
    public void messagePlayer(String player, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    @Override
    public void titlePlayer(String player, String mainTitle, String subTitle) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            if (p != null && p.isOnline()) {
                p.sendTitle(mainTitle == null ? "" : mainTitle,
                        subTitle == null ? "" : subTitle, 10, 60, 15);
            }
        });
    }
}
