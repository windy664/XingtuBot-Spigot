package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.lock.LockState;
import org.windy.xingtubot.common.lock.PlayerLockManager;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bukkit 端玩家登录锁管理器（packetevents 包级拦截）。
 * <p>独立模式（无代理）时使用。与 {@code VelocityPlayerLock} 功能对等。
 */
public class BukkitPlayerLock implements PlayerLockManager {

    private final Plugin plugin;
    private final LockState lockState = new LockState();
    private volatile BindingService bindingService;

    private final Map<String, LockData> lockData = new ConcurrentHashMap<>();
    private final Set<String> awaitingQQ = ConcurrentHashMap.newKeySet();

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
        lockData.put(key, new LockData());
        awaitingQQ.add(key);
    }

    @Override
    public void unlock(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        lockState.unlock(key);
        lockData.remove(key);
        awaitingQQ.remove(key);
        // 发解锁消息
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(player);
            if (p != null && p.isOnline()) {
                p.sendMessage("§a✅ 已登录，祝游戏愉快！");
            }
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
    }

    // ===== 状态查询 =====

    public boolean isAwaitingQQ(String player) {
        return awaitingQQ.contains(player.toLowerCase(Locale.ROOT));
    }

    public boolean isBound(String player) {
        return bindingService != null && bindingService.isPlayerBound(player);
    }

    // ===== 锁定坐标 =====

    public boolean capturePosition(String player, double x, double y, double z,
                                   float yaw, float pitch) {
        LockData data = lockData.get(player.toLowerCase(Locale.ROOT));
        if (data == null || data.hasPosition()) return false;
        data.setPosition(x, y, z, yaw, pitch);
        return true;
    }

    public LockData getLockData(String player) {
        return lockData.get(player.toLowerCase(Locale.ROOT));
    }

    // ===== QQ 输入处理 =====

    public void handleChatInput(Player player, String name, String message) {
        String trimmed = message.trim();
        if (!trimmed.matches("\\d{5,12}")) {
            player.sendMessage("§c请输入有效的 QQ 号（5-12 位数字）");
            return;
        }
        if (bindingService == null) {
            player.sendMessage("§c绑定服务未就绪，请稍后再试");
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
            if (!isLocked(name)) continue;

            if (isBound(name)) {
                p.sendTitle("§a§l欢迎回来 · 请登录",
                        "§f在群里点机器人发的「§a登录§f」按钮", 0, 80, 10);
            } else if (!isAwaitingQQ(name)) {
                p.sendTitle("§6§l就差一步 · 请完成绑定",
                        "§f在群里发送「§b绑定§f」完成头像验证", 0, 80, 10);
            } else {
                p.sendTitle("§6§l欢迎 · 请绑定白名单",
                        "§f在聊天框输入你的 §bQQ号", 0, 80, 10);
            }
        }
    }

    // ===== 锁定坐标数据 =====

    public static class LockData {
        private volatile double x, y, z;
        private volatile float yaw, pitch;
        private volatile boolean hasPosition;

        void setPosition(double x, double y, double z, float yaw, float pitch) {
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
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
