package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.lock.LockState;
import org.windy.xingtubot.common.lock.PlayerLockManager;
import org.windy.xingtubot.common.whitelist.LockBossBar;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.common.whitelist.LockPosition;
import org.windy.xingtubot.common.whitelist.LockPrompt;
import org.windy.xingtubot.common.whitelist.LockTarget;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * BungeeCord 端玩家登录锁管理器。实现 {@link LockTarget}，由三端共享的 {@code LockPacketListener} 驱动。
 * <p>与 {@code VelocityPlayerLock} 功能对等。
 */
public class BungeeCordPlayerLock implements PlayerLockManager, LockTarget {

    private final ProxyServer proxy;
    private final Plugin plugin;
    private final LockState lockState = new LockState();
    private volatile BindingService bindingService;

    private final Map<String, LockPosition> lockData = new ConcurrentHashMap<>();
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();
    private final Map<String, ScheduledTask> reminderTasks = new ConcurrentHashMap<>();
    // 常驻 bossbar（三端共用 packetevents 实现）
    private final LockBossBar bossBar = new LockBossBar();

    private volatile Consumer<String> onCodeIssued;

    public BungeeCordPlayerLock(ProxyServer proxy, Plugin plugin, BindingService bindingService) {
        this.proxy = proxy;
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
        startReminder(player);
    }

    @Override
    public void unlock(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        lockState.unlock(key);
        lockData.remove(key);
        awaitingQQ.remove(key);
        stopReminder(player);
        bossBar.clear(proxy.getPlayer(player), player);
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
        stopReminder(player);
        bossBar.clear(proxy.getPlayer(player), player);
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
        ProxiedPlayer player = proxy.getPlayer(name);
        if (player == null || !player.isConnected()) return;
        String trimmed = message.trim();

        if (!trimmed.matches("\\d{5,12}")) {
            player.sendMessage(LockMessages.qqInvalid());
            return;
        }
        if (bindingService == null) {
            player.sendMessage(LockMessages.notReady());
            return;
        }
        proxy.getScheduler().runAsync(plugin, () -> {
            BindingService.Result r = bindingService.declareQQ(name, trimmed);
            proxy.getScheduler().schedule(plugin, () -> {
                ProxiedPlayer p = proxy.getPlayer(name);
                if (p == null || !p.isConnected()) return;
                p.sendMessage(r.message);
                if (r.success) {
                    awaitingQQ.remove(name.toLowerCase(Locale.ROOT));
                    restartReminder(name);
                    Consumer<String> cb = onCodeIssued;
                    if (cb != null) {
                        try { cb.accept(name); } catch (Exception ignored) {}
                    }
                }
            }, 0, TimeUnit.MILLISECONDS);
        });
    }

    // ===== 定时提醒 =====

    private void startReminder(String player) {
        stopReminder(player);
        ScheduledTask task = proxy.getScheduler().schedule(plugin, () -> tickReminder(player),
                3, 3, TimeUnit.SECONDS);
        reminderTasks.put(player.toLowerCase(Locale.ROOT), task);
    }

    private void stopReminder(String player) {
        ScheduledTask task = reminderTasks.remove(player.toLowerCase(Locale.ROOT));
        if (task != null) task.cancel();
    }

    private void restartReminder(String player) {
        stopReminder(player);
        startReminder(player);
    }

    private void tickReminder(String player) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p == null || !p.isConnected()) {
            bossBar.clear(p, player);
            stopReminder(player);
            return;
        }
        if (!isLocked(player)) {
            bossBar.clear(p, player);
            stopReminder(player);
            return;
        }
        boolean bound = isBound(player);
        bossBar.set(p, player, LockPrompt.text(bound, isAwaitingQQ(player)), bound);
    }
}
