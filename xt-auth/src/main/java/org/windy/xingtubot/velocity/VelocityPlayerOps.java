package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.windy.xingtubot.common.whitelist.PlatformPlayerOps;

/**
 * Velocity 平台操作：getPlayer 走 ProxyServer API，发包走基类 packetevents。
 */
public class VelocityPlayerOps extends PlatformPlayerOps {

    private final ProxyServer proxy;

    public VelocityPlayerOps(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public Object getPlayer(String name) {
        return proxy.getPlayer(name).orElse(null);
    }

    @Override
    public boolean isOnline(Object player) {
        return player instanceof Player && ((Player) player).isActive();
    }

    @Override
    public void sendMessage(Object player, String message) {
        if (player instanceof Player) {
            ((Player) player).sendMessage(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().deserialize(message == null ? "" : message));
        }
    }
}
