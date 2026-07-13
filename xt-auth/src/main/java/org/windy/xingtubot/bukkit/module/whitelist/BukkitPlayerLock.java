package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.whitelist.AbstractPlayerLock;
import org.windy.xingtubot.common.whitelist.PlatformPlayerOps;

/**
 * Bukkit 端玩家登录锁管理器。
 */
public class BukkitPlayerLock extends AbstractPlayerLock {

    private final PlatformPlayerOps ops;

    public BukkitPlayerLock(Plugin plugin, BindingService bindingService) {
        super(bindingService);
        this.ops = new BukkitPlayerOps(plugin);
    }

    @Override
    protected PlatformPlayerOps ops() {
        return ops;
    }

    /** 全局遍历提醒（WhitelistModule 每 3 秒调用）。 */
    public void tickReminder() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        tickReminderForAll(names);
    }
}
