package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.lock.LockState;
import org.windy.xingtubot.common.lock.PlayerLockManager;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Velocity 端玩家登录锁管理器（packetevents 包级拦截 + Velocity 事件层门禁）。
 *
 * <p>职责：
 * <ul>
 *   <li>锁定/解锁状态（{@link LockState}）</li>
 *   <li>锁定坐标（从首个 Position 包捕获）</li>
 *   <li>awaitingQQ 状态（未绑定玩家等输入 QQ 号）</li>
 *   <li>定时登录提醒（title + message，Velocity 直发）</li>
 *   <li>QQ 号输入处理（替代 Bukkit WhitelistModule 的聊天监听）</li>
 * </ul>
 *
 * <p>扩展点：
 * <ul>
 *   <li>{@link #setQrMapProvider} — 加群二维码地图提供者（TODO：以后实现）</li>
 *   <li>{@link #setOnCodeIssued} — QQ 登记成功后的回调（挂接二维码等）</li>
 * </ul>
 */
public class VelocityPlayerLock implements PlayerLockManager {

    private final ProxyServer proxy;
    private final Object plugin; // VelocityPlugin 实例，用于 scheduler
    private final LockState lockState = new LockState();
    private volatile BindingService bindingService;

    // 锁定坐标数据
    private final Map<String, LockData> lockData = new ConcurrentHashMap<>();
    // 正在等待输入 QQ 号的玩家
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();
    // 定时提醒任务句柄（按玩家名）
    private final Map<String, com.velocitypowered.api.scheduler.ScheduledTask> reminderTasks
            = new ConcurrentHashMap<>();

    // ===== 扩展点 =====

    /**
     * 加群二维码地图提供者（TODO：以后实现）。
     * <p>签名：{@code void giveQrMap(Player player)}，实现者发地图包给玩家。
     * <p>设为 null 表示不发二维码地图。
     */
    private volatile Consumer<Player> qrMapProvider;

    /** QQ 登记成功后的回调（进入「去群里发『绑定』」阶段时触发）。 */
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

    // ===== 扩展点 setter =====

    /**
     * 设置加群二维码地图提供者（TODO：以后实现）。
     * <p>调用时机：玩家 QQ 登记成功、进入「去群里发『绑定』」阶段。
     */
    public void setQrMapProvider(Consumer<Player> provider) {
        this.qrMapProvider = provider;
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
        lockData.put(key, new LockData());
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
    }

    @Override
    public boolean isLocked(String player) {
        return lockState.isLocked(player);
    }

    public boolean isAwaitingQQ(String player) {
        return awaitingQQ.contains(player.toLowerCase(Locale.ROOT));
    }

    public boolean isBound(String player) {
        return bindingService != null && bindingService.isPlayerBound(player);
    }

    // ===== 锁定坐标（从首个 Position 包捕获） =====

    /**
     * 从首个 Position 包捕获锁定坐标（只取一次）。
     *
     * @return true 如果坐标是首次捕获（调用者应发一次 Position 包拉回）
     */
    public boolean capturePosition(String player, double x, double y, double z,
                                   float yaw, float pitch) {
        LockData data = lockData.get(player.toLowerCase(Locale.ROOT));
        if (data == null || data.hasPosition()) return false;
        data.setPosition(x, y, z, yaw, pitch);
        return true;
    }

    /** 获取锁定坐标并构造 Position 包数据。返回 null 如果还没有坐标。 */
    public LockData getLockData(String player) {
        return lockData.get(player.toLowerCase(Locale.ROOT));
    }

    // ===== QQ 号输入处理（替代 Bukkit WhitelistModule 的聊天监听） =====

    /**
     * 处理未绑定玩家在聊天框输入的内容（尝试解析为 QQ 号）。
     * <p>在 packetevents 的 onPacketReceive 中调用（已在异步线程）。
     */
    public void handleChatInput(Player player, String name, String message) {
        String trimmed = message.trim();

        // 简单校验：QQ 号是 5-12 位数字
        if (!trimmed.matches("\\d{5,12}")) {
            player.sendMessage(legacy("§c请输入有效的 QQ 号（5-12 位数字）"));
            return;
        }

        if (bindingService == null) {
            player.sendMessage(legacy("§c绑定服务未就绪，请稍后再试"));
            return;
        }

        // 异步调用 BindingService.declareQQ（会下载头像）
        proxy.getScheduler().buildTask(plugin, () -> {
            BindingService.Result r = bindingService.declareQQ(name, trimmed);
            // 回主线程发消息
            proxy.getScheduler().buildTask(plugin, () -> {
                Player p = proxy.getPlayer(name).orElse(null);
                if (p == null) return;
                p.sendMessage(legacy(r.message));
                if (r.success) {
                    awaitingQQ.remove(name.toLowerCase(Locale.ROOT));
                    // 更新提醒标题为「请完成绑定」阶段
                    restartReminder(name);
                    // 触发回调（加群二维码等 TODO）
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
        // 3 秒间隔
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
            stopReminder(player);
            return;
        }
        if (!isLocked(player)) {
            stopReminder(player);
            return;
        }

        int stay = 80; // 4 秒停留（tick）

        if (isBound(player)) {
            // 已绑定 → 等群里发「登录」
            p.showTitle(Title.title(
                    legacy("§a§l欢迎回来 · 请登录"),
                    legacy("§f在群里点机器人发的「§a登录§f」按钮"),
                    Title.Times.of(Duration.ZERO, Duration.ofMillis(stay * 50L), Duration.ofMillis(500))));
        } else if (!isAwaitingQQ(player)) {
            // 已声明 QQ → 等群里发「绑定」
            p.showTitle(Title.title(
                    legacy("§6§l就差一步 · 请完成绑定"),
                    legacy("§f在群里发送「§b绑定§f」完成头像验证"),
                    Title.Times.of(Duration.ZERO, Duration.ofMillis(stay * 50L), Duration.ofMillis(500))));
        } else {
            // 还没输 QQ 号
            p.showTitle(Title.title(
                    legacy("§6§l欢迎 · 请绑定白名单"),
                    legacy("§f在聊天框输入你的 §bQQ号"),
                    Title.Times.of(Duration.ZERO, Duration.ofMillis(stay * 50L), Duration.ofMillis(500))));
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
    }

    // ===== 工具方法 =====

    static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    // ===== 锁定坐标数据 =====

    public static class LockData {
        private volatile double x, y, z;
        private volatile float yaw, pitch;
        private volatile boolean hasPosition;

        void setPosition(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.hasPosition = true;
        }

        public boolean hasPosition() { return hasPosition; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public float getYaw() { return yaw; }
        public float getPitch() { return pitch; }
    }
}
