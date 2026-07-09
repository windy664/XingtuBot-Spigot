package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.lock.LockState;
import org.windy.xingtubot.common.lock.PlayerLockManager;
import org.windy.xingtubot.common.whitelist.LockBossBar;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.common.whitelist.LockPosition;
import org.windy.xingtubot.common.whitelist.LockPrompt;
import org.windy.xingtubot.common.whitelist.LockTarget;
import org.windy.xingtubot.common.whitelist.LockTitle;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bukkit 端玩家登录锁管理器。实现 {@link LockTarget}，由三端共享的 {@code LockPacketListener} 驱动。
 * <p>独立模式（无代理）时使用。与 {@code VelocityPlayerLock} 功能对等。
 */
public class BukkitPlayerLock implements PlayerLockManager, LockTarget {

    private final Plugin plugin;
    private final LockState lockState = new LockState();
    private volatile BindingService bindingService;

    private final Map<String, LockPosition> lockData = new ConcurrentHashMap<>();
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();
    // 常驻 bossbar（三端共用 packetevents 实现）——取代闪烁的 title
    private final LockBossBar bossBar = new LockBossBar();

    private volatile Consumer<String> onCodeIssued;

    public BukkitPlayerLock(Plugin plugin, BindingService bindingService) {
        this.plugin = plugin;
        this.bindingService = bindingService;
    }

    public void setBindingService(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    public void setOnCodeIssued(Consumer<String> callback) {
        this.onCodeIssued = callback;
    }

    // ===== PlayerLockManager =====

    @Override
    public void lock(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        lockState.lock(key);
        lockData.put(key, new LockPosition());
        awaitingQQ.add(key);
    }

    @Override
    public void unlock(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        lockState.unlock(key);
        lockData.remove(key);
        awaitingQQ.remove(key);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            bossBar.clear(p, player);
            // 不在此处发消息——由调用方（AuthAdapter / WhitelistModule）按上下文发
            // （login → unlocked，register → bound，auto-login → auto-login-msg）
        });
    }

    @Override
    public boolean isLocked(String player) {
        return lockState.isLocked(player);
    }

    @Override
    public void onDisconnect(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        awaitingQQ.remove(key);
        lockData.remove(key);
        lockState.clear(key);
        bossBar.clear(Bukkit.getPlayerExact(player), player);
    }

    // ===== 状态查询 =====

    @Override
    public boolean isAwaitingQQ(String player) {
        return awaitingQQ.contains(player.toLowerCase(Locale.ROOT));
    }

    public boolean isBound(String player) {
        return bindingService != null && bindingService.isPlayerBound(player);
    }

    // ===== 锁定坐标 =====

    @Override
    public boolean capturePosition(String player, double x, double y, double z,
                                   float yaw, float pitch) {
        LockPosition data = lockData.get(player.toLowerCase(Locale.ROOT));
        if (data == null || data.hasPosition()) return false;
        data.setPosition(x, y, z, yaw, pitch);
        return true;
    }

    @Override
    public LockPosition getLockData(String player) {
        return lockData.get(player.toLowerCase(Locale.ROOT));
    }

    // ===== QQ 输入处理 =====

    @Override
    public void handleChatInput(String name, String message) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || !player.isOnline()) return;
        String trimmed = message.trim();

        if (!trimmed.matches("\\d{5,12}")) {
            player.sendMessage(LockMessages.qqInvalid());
            return;
        }
        if (bindingService == null) {
            player.sendMessage(LockMessages.notReady());
            return;
        }
        // 异步调用 BindingService.declareQQ
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BindingService.Result r = bindingService.declareQQ(name, trimmed);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null || !p.isOnline()) return;
                p.sendMessage(r.message);
                if (r.success) {
                    awaitingQQ.remove(name.toLowerCase(Locale.ROOT));
                    Consumer<String> cb = onCodeIssued;
                    if (cb != null) {
                        try { cb.accept(name); } catch (Exception ignored) {}
                    }
                }
            });
        });
    }

    // ===== 定时提醒（由 WhitelistModule 的 startReminderTask 驱动） =====

    public void tickReminder() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = p.getName();
            if (!isLocked(name)) {
                bossBar.clear(p, name); // 在线但已解锁：清掉可能残留的 bar
                continue;
            }
            boolean bound = isBound(name);
            boolean awaiting = isAwaitingQQ(name);
            String text = LockPrompt.text(bound, awaiting);
            if (LockPrompt.isQrPhase(bound, awaiting)) {
                bossBar.set(p, name, text, false); // QR 阶段：bossbar，不挡手里的地图
            } else {
                bossBar.clear(p, name);
                LockTitle.send(p, text); // 其余阶段：title
            }
        }
    }
}
