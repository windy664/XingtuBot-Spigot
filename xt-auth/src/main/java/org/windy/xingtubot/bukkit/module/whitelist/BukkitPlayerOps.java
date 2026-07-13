package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.whitelist.PlatformPlayerOps;

/**
 * Bukkit 平台操作：getPlayer 走 Bukkit API，发包走基类 packetevents。
 */
public class BukkitPlayerOps extends PlatformPlayerOps {

    private final Plugin plugin;

    public BukkitPlayerOps(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Object getPlayer(String name) {
        return Bukkit.getPlayerExact(name);
    }

    @Override
    public boolean isOnline(Object player) {
        return player instanceof Player && ((Player) player).isOnline();
    }

    @Override
    public void sendMessage(Object player, String message) {
        if (!(player instanceof Player)) return;
        Runnable action = () -> ((Player) player).sendMessage(message);
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }
}
