package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.util.UUID;

/**
 * Spigot 平台适配。
 */
public class SpigotAdapter implements PlatformAdapter {
    private final Plugin plugin;

    public SpigotAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAsync(Runnable r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    @Override
    public void runSync(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    @Override
    public void log(String msg) {
        // 仅调试模式输出，与原 WebSocket 调试日志行为一致
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(msg);
        }
    }

    @Override
    public void broadcast(String msg) {
        Bukkit.broadcastMessage(msg);
    }

    @Override
    public void sendMessageToPlayer(UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
    }
}
