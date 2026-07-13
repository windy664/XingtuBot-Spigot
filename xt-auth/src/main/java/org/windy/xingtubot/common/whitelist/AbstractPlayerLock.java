package org.windy.xingtubot.common.whitelist;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.lock.LockState;
import org.windy.xingtubot.common.lock.PlayerLockManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 玩家登录锁的平台无关基类。三端共用全部状态管理、核心逻辑和 per-player 提醒调度。
 * 平台差异仅通过 {@link PlatformPlayerOps}（发包/查玩家）隔离。
 */
public abstract class AbstractPlayerLock implements PlayerLockManager, LockTarget {

    private final LockState lockState = new LockState();
    private volatile BindingService bindingService;

    private final Map<String, LockPosition> lockData = new ConcurrentHashMap<>();
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();
    protected final LockBossBar bossBar = new LockBossBar();

    /** 锁期缓存的真实背包（键=小写名），供解锁恢复。 */
    private final Map<String, InventorySnapshot> capturedInventory = new ConcurrentHashMap<>();

    private volatile Consumer<String> onCodeIssued;

    // per-player 提醒定时器（平台无关，用 Java ScheduledExecutorService）
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PlayerLock-Reminder");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> reminderTasks = new ConcurrentHashMap<>();

    protected AbstractPlayerLock(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    /** 返回平台操作抽象（查玩家/发消息）。 */
    protected abstract PlatformPlayerOps ops();

    // ==================== 配置 ====================

    public void setBindingService(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    public void setOnCodeIssued(Consumer<String> callback) {
        this.onCodeIssued = callback;
    }

    // ==================== PlayerLockManager ====================

    @Override
    public void lock(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        lockState.lock(key);
        lockData.put(key, new LockPosition());
        awaitingQQ.add(key);
        startReminder(player);
        // 立即抹空背包显示（后端稍后下发真实背包时 onPacketSend 会捕获并再抹一次）
        Object p = ops().getPlayer(player);
        if (p != null && ops().isOnline(p)) InventoryMask.clear(p);
    }

    @Override
    public void unlock(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        lockState.unlock(key);
        lockData.remove(key);
        awaitingQQ.remove(key);
        stopReminder(player);
        Object p = ops().getPlayer(player);
        bossBar.clear(p, player);
        // 恢复真实背包（把锁期缓存的内容发回；无缓存则不动，客户端首次交互会自行 resync）
        InventorySnapshot snap = capturedInventory.remove(key);
        if (snap != null && p != null && ops().isOnline(p)) {
            InventoryMask.restore(p, snap.items, snap.carried);
        }
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
        bossBar.clear(null, player);
        capturedInventory.remove(key);
    }

    // ==================== 状态查询 ====================

    @Override
    public boolean isAwaitingQQ(String player) {
        return awaitingQQ.contains(player.toLowerCase(Locale.ROOT));
    }

    public boolean isBound(String player) {
        return bindingService != null && bindingService.isPlayerBound(player);
    }

    // ==================== 锁定坐标 ====================

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

    // ==================== 背包遮罩缓存 ====================

    @Override
    public boolean hasCapturedInventory(String name) {
        return capturedInventory.containsKey(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void captureInventory(String name, List<ItemStack> items, ItemStack carried) {
        capturedInventory.put(name.toLowerCase(Locale.ROOT), new InventorySnapshot(items, carried));
    }

    /** 锁期缓存的真实背包快照（窗口 0 全槽 + 光标）。 */
    private static final class InventorySnapshot {
        final List<ItemStack> items;
        final ItemStack carried;
        InventorySnapshot(List<ItemStack> items, ItemStack carried) {
            this.items = items;
            this.carried = carried;
        }
    }

    // ==================== QQ 输入处理 ====================

    @Override
    public void handleChatInput(String name, String message) {
        Object player = ops().getPlayer(name);
        if (player == null || !ops().isOnline(player)) return;
        String trimmed = message.trim();

        if (!trimmed.matches("\\d{5,12}")) {
            ops().sendMessage(player, LockMessages.qqInvalid());
            return;
        }
        if (bindingService == null) {
            ops().sendMessage(player, LockMessages.notReady());
            return;
        }
        // declareQQ 会下载头像，必须异步
        new Thread(() -> {
            BindingService.Result r = bindingService.declareQQ(name, trimmed);
            ops().runOnMain(() -> {
                Object p = ops().getPlayer(name);
                if (p == null || !ops().isOnline(p)) return;
                ops().sendMessage(p, r.message);
                if (r.success) {
                    awaitingQQ.remove(name.toLowerCase(Locale.ROOT));
                    restartReminder(name);
                    Consumer<String> cb = onCodeIssued;
                    if (cb != null) {
                        try { cb.accept(name); } catch (Exception ignored) {}
                    }
                }
            });
        }, "declareQQ-" + name).start();
    }

    // ==================== per-player 提醒（平台无关） ====================

    private void startReminder(String player) {
        stopReminder(player);
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> {
                    if (!tickReminderFor(player)) stopReminder(player);
                }, 3, 3, TimeUnit.SECONDS);
        reminderTasks.put(player.toLowerCase(Locale.ROOT), task);
    }

    private void stopReminder(String player) {
        ScheduledFuture<?> task = reminderTasks.remove(player.toLowerCase(Locale.ROOT));
        if (task != null) task.cancel(false);
    }

    private void restartReminder(String player) {
        stopReminder(player);
        startReminder(player);
    }

    /**
     * 对单个玩家渲染提醒（bossbar / title）。
     * @return true 表示仍需继续提醒，false 表示已解锁或离线。
     */
    private boolean tickReminderFor(String player) {
        Object p = ops().getPlayer(player);
        if (p == null || !ops().isOnline(p)) {
            bossBar.clear(null, player);
            return false;
        }
        if (!isLocked(player)) {
            bossBar.clear(p, player);
            return false;
        }
        boolean bound = isBound(player);
        boolean awaiting = isAwaitingQQ(player);
        String text = LockPrompt.text(bound, awaiting);
        if (LockPrompt.isQrPhase(bound, awaiting)) {
            // 二维码阶段：背包已在锁定时抹空，此处不再重抹（避免盖掉快捷栏里的二维码地图）。
            bossBar.set(p, player, text, false);
        } else {
            bossBar.clear(p, player);
            LockTitle.send(p, text);
            InventoryMask.clear(p); // 持续抹空背包显示（后端若重推，3s 内盖回空）
        }
        return true;
    }

    // ==================== Bukkit 全局遍历模式（可选） ====================

    /**
     * Bukkit 本地模式的全局遍历提醒（WhitelistModule 的 startReminderTask 每 3 秒调用）。
     * 代理模式（Velocity/BungeeCord）不用这个——用上面的 per-player startReminder。
     */
    protected void tickReminderForAll(Iterable<String> onlinePlayers) {
        for (String name : onlinePlayers) {
            tickReminderFor(name);
        }
    }
}
