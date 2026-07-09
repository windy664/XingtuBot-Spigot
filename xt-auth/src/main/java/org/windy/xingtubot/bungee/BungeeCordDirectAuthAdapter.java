package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.whitelist.LockMessages;

/**
 * BungeeCord 端直通 {@link AuthAdapter}：unlock / message / title 全在代理侧完成。
 * <p>与 {@code VelocityDirectAuthAdapter} 功能对等。
 */
public class BungeeCordDirectAuthAdapter implements AuthAdapter {

    private final ProxyServer proxy;
    private final BungeeCordPlayerLock lock;

    public BungeeCordDirectAuthAdapter(ProxyServer proxy, BungeeCordPlayerLock lock) {
        this.proxy = proxy;
        this.lock = lock;
    }

    @Override
    public boolean isOnline(String player) {
        ProxiedPlayer p = proxy.getPlayer(player);
        return p != null && p.isConnected();
    }

    @Override
    public void register(String player) {
        lock.unlock(player);
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null && p.isConnected()) {
            p.sendMessage(new TextComponent(LockMessages.get("bound")));
        }
    }

    @Override
    public void login(String player) {
        lock.unlock(player);
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null && p.isConnected()) {
            p.sendMessage(new TextComponent(LockMessages.unlocked()));
        }
    }

    @Override
    public void messagePlayer(String player, String message) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null && p.isConnected()) {
            p.sendMessage(message);
        }
    }

    @Override
    public void titlePlayer(String player, String mainTitle, String subTitle) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null && p.isConnected()) {
            // BungeeCord 没有原生 title API，发聊天消息代替
            p.sendMessage(mainTitle);
            if (subTitle != null && !subTitle.isEmpty()) {
                p.sendMessage(subTitle);
            }
        }
    }
}
