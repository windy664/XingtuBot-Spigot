package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.windy.xingtubot.common.whitelist.PlatformPlayerOps;

/**
 * BungeeCord 平台操作：getPlayer 走 ProxyServer API，发包走基类 packetevents。
 */
public class BungeeCordPlayerOps extends PlatformPlayerOps {

    private final ProxyServer proxy;

    public BungeeCordPlayerOps(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public Object getPlayer(String name) {
        return proxy.getPlayer(name);
    }

    @Override
    public boolean isOnline(Object player) {
        return player instanceof ProxiedPlayer && ((ProxiedPlayer) player).isConnected();
    }

    @Override
    public void sendMessage(Object player, String message) {
        if (player instanceof ProxiedPlayer) {
            ((ProxiedPlayer) player).sendMessage(message);
        }
    }
}
