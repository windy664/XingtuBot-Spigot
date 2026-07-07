package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.lock.LockState;

/**
 * 自研登录的 {@link AuthAdapter} 实现，取代 AuthMe。
 *
 * <p>无密码、无外部认证插件：登录/注册=「解锁」（让玩家能动），其余时间由
 * {@link PlayerLockListener} 冻结。register 与 login 语义在自研锁里相同（都=解锁）。
 */
public class LockAuthAdapter implements AuthAdapter {

    private final Plugin plugin;
    private final LockState lockState;

    public LockAuthAdapter(Plugin plugin, LockState lockState) {
        this.plugin = plugin;
        this.lockState = lockState;
    }

    @Override
    public boolean isOnline(String player) {
        Player p = Bukkit.getPlayerExact(player);
        return p != null && p.isOnline();
    }

    @Override
    public void register(String player) {
        unlock(player); // 绑定成功即解锁
    }

    @Override
    public void login(String player) {
        unlock(player);
    }

    private void unlock(String player) {
        lockState.unlock(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            if (p != null && p.isOnline()) {
                JoinQrMap.cleanup(p); // 清掉加群二维码地图（绑定成功后不再需要）
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
                // fadeIn 0.5s / stay 3s / fadeOut 0.75s（单位 tick）
                p.sendTitle(mainTitle == null ? "" : mainTitle,
                        subTitle == null ? "" : subTitle, 10, 60, 15);
            }
        });
    }
}
