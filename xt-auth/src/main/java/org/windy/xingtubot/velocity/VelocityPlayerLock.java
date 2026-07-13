package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import org.windy.xingtubot.common.binding.AutoLoginRepository;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.whitelist.AbstractPlayerLock;
import org.windy.xingtubot.common.whitelist.PlatformPlayerOps;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Velocity 端玩家登录锁管理器。额外支持 IP 自动登录信任期。
 */
public class VelocityPlayerLock extends AbstractPlayerLock {

    private final PlatformPlayerOps ops;

    // ===== IP 自动登录信任期 =====
    private final Map<String, AutoLoginEntry> autoLoginMem = new ConcurrentHashMap<>();
    private volatile AutoLoginRepository autoLoginRepo;
    private volatile long autoLoginWindowMillis = 0L;
    private volatile Consumer<String> onNeedLogin;

    public VelocityPlayerLock(ProxyServer proxy, BindingService bindingService) {
        super(bindingService);
        this.ops = new VelocityPlayerOps(proxy);
    }

    @Override
    protected PlatformPlayerOps ops() {
        return ops;
    }

    // ==================== 自动登录 ====================

    public void setAutoLoginWindowMillis(long millis) {
        this.autoLoginWindowMillis = Math.max(0L, millis);
    }

    public void setAutoLoginRepository(AutoLoginRepository repo) {
        this.autoLoginRepo = repo;
    }

    public void setOnNeedLogin(Consumer<String> callback) {
        this.onNeedLogin = callback;
    }

    public boolean autoLoginAllowed(String player, String ip) {
        if (autoLoginWindowMillis <= 0 || ip == null) return false;
        AutoLoginEntry e = autoLoginGet(player);
        if (e == null) return false;
        if (System.currentTimeMillis() >= e.expiry) {
            autoLoginRemove(player);
            return false;
        }
        return ip.equals(e.ip);
    }

    public void armAutoLogin(String player, String ip) {
        if (autoLoginWindowMillis <= 0 || ip == null) return;
        autoLoginPut(player, ip, System.currentTimeMillis() + autoLoginWindowMillis);
    }

    public void fireNeedLogin(String player) {
        Consumer<String> cb = onNeedLogin;
        if (cb != null) {
            try { cb.accept(player); } catch (Exception ignored) {}
        }
    }

    private void autoLoginPut(String player, String ip, long expiry) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.put(player, ip, expiry);
        else autoLoginMem.put(player.toLowerCase(Locale.ROOT), new AutoLoginEntry(ip, expiry));
    }

    private AutoLoginEntry autoLoginGet(String player) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) {
            AutoLoginRepository.Entry e = r.get(player);
            return e == null ? null : new AutoLoginEntry(e.ip, e.expiry);
        }
        return autoLoginMem.get(player.toLowerCase(Locale.ROOT));
    }

    private void autoLoginRemove(String player) {
        AutoLoginRepository r = autoLoginRepo;
        if (r != null) r.remove(player);
        else autoLoginMem.remove(player.toLowerCase(Locale.ROOT));
    }

    private static final class AutoLoginEntry {
        final String ip;
        final long expiry;
        AutoLoginEntry(String ip, long expiry) { this.ip = ip; this.expiry = expiry; }
    }
}
