package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
 * Velocity 端玩家登录锁管理器。锁定状态 + 坐标 + awaitingQQ + bossbar 提醒 + QQ 输入处理。
 * <p>实现 {@link LockTarget}，由三端共享的 {@code LockPacketListener} 驱动。
 */
public class VelocityPlayerLock implements PlayerLockManager, LockTarget {

    private final ProxyServer proxy;
    private final Object plugin; // VelocityPlugin 实例，用于 scheduler
    private final LockState lockState = new LockState();
    private volatile BindingService bindingService;

    private final Map<String, LockPosition> lockData = new ConcurrentHashMap<>();
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();
    private final Map<String, com.velocitypowered.api.scheduler.ScheduledTask> reminderTasks
            = new ConcurrentHashMap<>();
    // 常驻 bossbar（三端共用 packetevents 实现）——取代闪烁的 title 作为锁定期引导
    private final LockBossBar bossBar = new LockBossBar();

    /** QQ 登记成功后的回调（进入「去群里发『绑定』」阶段时触发，用于发加群二维码等）。 */
    private volatile Consumer<String> onCodeIssued;

    public VelocityPlayerLock(ProxyServer proxy, Object plugin, BindingService bindingService) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.bindingService = bindingService;
    }

    /** 延迟注入 BindingService（enable() 之后补全）。 */
    public void setBindingService(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    /** 设置 QQ 登记成功后的回调。 */
    public void setOnCodeIssued(Consumer<String> callback) {
        this.onCodeIssued = callback;
    }

    // ===== 锁定状态 =====

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
        bossBar.clear(proxy.getPlayer(player).orElse(null), player);
    }

    @Override
    public boolean isLocked(String player) {
        return lockState.isLocked(player);
    }

    @Override
    public boolean isAwaitingQQ(String player) {
        return awaitingQQ.contains(player.toLowerCase(Locale.ROOT));
    }

    public boolean isBound(String player) {
        return bindingService != null && bindingService.isPlayerBound(player);
    }

    // ===== 锁定坐标（从首个 Position 包捕获） =====

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

    // ===== QQ 号输入处理 =====

    @Override
    public void handleChatInput(String name, String message) {
        Player player = proxy.getPlayer(name).orElse(null);
        if (player == null) return;
        String trimmed = message.trim();

        // 简单校验：QQ 号是 5-12 位数字
        if (!trimmed.matches("\\d{5,12}")) {
            player.sendMessage(legacy(LockMessages.qqInvalid()));
            return;
        }
        if (bindingService == null) {
            player.sendMessage(legacy(LockMessages.notReady()));
            return;
        }

        // 异步调用 BindingService.declareQQ（会下载头像）
        proxy.getScheduler().buildTask(plugin, () -> {
            BindingService.Result r = bindingService.declareQQ(name, trimmed);
            proxy.getScheduler().buildTask(plugin, () -> {
                Player p = proxy.getPlayer(name).orElse(null);
                if (p == null) return;
                p.sendMessage(legacy(r.message));
                if (r.success) {
                    awaitingQQ.remove(name.toLowerCase(Locale.ROOT));
                    restartReminder(name);
                    Consumer<String> cb = onCodeIssued;
                    if (cb != null) {
                        try { cb.accept(name); } catch (Exception ignored) {}
                    }
                }
            }).schedule();
        }).schedule();
    }

    // ===== 定时提醒 =====

    private void startReminder(String player) {
        stopReminder(player); // 避免重复
        com.velocitypowered.api.scheduler.ScheduledTask task = proxy.getScheduler()
                .buildTask(plugin, () -> tickReminder(player))
                .delay(3, java.util.concurrent.TimeUnit.SECONDS)
                .repeat(3, java.util.concurrent.TimeUnit.SECONDS)
                .schedule();
        reminderTasks.put(player.toLowerCase(Locale.ROOT), task);
    }

    private void stopReminder(String player) {
        com.velocitypowered.api.scheduler.ScheduledTask task =
                reminderTasks.remove(player.toLowerCase(Locale.ROOT));
        if (task != null) task.cancel();
    }

    private void restartReminder(String player) {
        stopReminder(player);
        startReminder(player);
    }

    private void tickReminder(String player) {
        Player p = proxy.getPlayer(player).orElse(null);
        if (p == null || !p.isActive()) {
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
        boolean awaiting = isAwaitingQQ(player);
        String text = LockPrompt.text(bound, awaiting);
        if (LockPrompt.isQrPhase(bound, awaiting)) {
            bossBar.set(p, player, text, false); // QR 阶段：bossbar，不挡手里的地图
        } else {
            bossBar.clear(p, player);
            LockTitle.send(p, text); // 其余阶段：title
        }
    }

    /** 清理玩家离线时的所有状态。 */
    @Override
    public void onDisconnect(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        awaitingQQ.remove(key);
        lockData.remove(key);
        lockState.clear(key);
        stopReminder(player);
        bossBar.clear(proxy.getPlayer(player).orElse(null), player);
    }

    // ===== 工具方法 =====

    static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }
}
