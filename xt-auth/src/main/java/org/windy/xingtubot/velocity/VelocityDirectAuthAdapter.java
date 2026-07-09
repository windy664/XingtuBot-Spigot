package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.whitelist.LockMessages;

import java.time.Duration;

/**
 * Velocity 端直通 {@link AuthAdapter}：unlock / message / title 全在 Velocity 侧完成。
 *
 * <p>与 {@link PluginMessageAuthAdapter} 的区别：
 * <ul>
 *   <li>PluginMessageAuthAdapter：login/register 通过 PluginMessage 发给子服执行（子服有 LockAuthAdapter）</li>
 *   <li>本类：login/register 直接调用 {@link VelocityPlayerLock#unlock}（子服不再有锁）</li>
 * </ul>
 *
 * <p>用于 packetevents 登录锁方案：Bukkit 端的 PlayerLockListener 已搁置，锁完全在 Velocity 层。
 */
public class VelocityDirectAuthAdapter implements AuthAdapter {

    private final ProxyServer proxy;
    private final VelocityPlayerLock lock;

    public VelocityDirectAuthAdapter(ProxyServer proxy, VelocityPlayerLock lock) {
        this.proxy = proxy;
        this.lock = lock;
    }

    @Override
    public boolean isOnline(String player) {
        return proxy.getPlayer(player).isPresent();
    }

    @Override
    public void register(String player) {
        lock.unlock(player);
        Player p = proxy.getPlayer(player).orElse(null);
        if (p != null && p.isActive()) {
            p.sendMessage(VelocityPlayerLock.legacy(LockMessages.get("bound")));
        }
    }

    @Override
    public void login(String player) {
        lock.unlock(player);
        Player p = proxy.getPlayer(player).orElse(null);
        if (p != null && p.isActive()) {
            p.sendMessage(VelocityPlayerLock.legacy(LockMessages.unlocked()));
        }
    }

    @Override
    public void messagePlayer(String player, String message) {
        proxy.getPlayer(player).ifPresent(p -> {
            if (p.isActive()) {
                p.sendMessage(VelocityPlayerLock.legacy(message));
            }
        });
    }

    @Override
    public void titlePlayer(String player, String mainTitle, String subTitle) {
        proxy.getPlayer(player).ifPresent(p -> {
            if (p.isActive()) {
                p.showTitle(Title.title(
                        VelocityPlayerLock.legacy(mainTitle),
                        VelocityPlayerLock.legacy(subTitle == null ? "" : subTitle)));
            }
        });
    }

    @Override
    public void titlePlayer(String player, String mainTitle, String subTitle,
                            int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Title.Times times = Title.Times.of(
                Duration.ofMillis(fadeInTicks * 50L),
                Duration.ofMillis(stayTicks * 50L),
                Duration.ofMillis(fadeOutTicks * 50L));
        proxy.getPlayer(player).ifPresent(p -> {
            if (p.isActive()) {
                p.showTitle(Title.title(
                        VelocityPlayerLock.legacy(mainTitle),
                        VelocityPlayerLock.legacy(subTitle == null ? "" : subTitle),
                        times));
            }
        });
    }
}
