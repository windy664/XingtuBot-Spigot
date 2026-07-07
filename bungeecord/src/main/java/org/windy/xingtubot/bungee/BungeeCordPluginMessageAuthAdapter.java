package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.Server;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.bridge.BridgeCodec;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;

/**
 * BungeeCord（大脑）侧的 {@link AuthAdapter}：把 AuthMe 操作通过 Plugin Message
 * 派发给玩家当前所在的 Spigot 子服执行。
 */
public class BungeeCordPluginMessageAuthAdapter implements AuthAdapter {

    private final ProxyServer proxy;
    private final String channel;

    public BungeeCordPluginMessageAuthAdapter(ProxyServer proxy, String channel) {
        this.proxy = proxy;
        this.channel = channel;
    }

    @Override
    public boolean isOnline(String player) {
        ProxiedPlayer p = proxy.getPlayer(player);
        return p != null && p.isConnected();
    }

    @Override
    public void register(String player) {
        send(player, BridgeCodec.encode(CrossServerProtocol.Type.DO_REGISTER, player));
    }

    @Override
    public void login(String player) {
        send(player, BridgeCodec.encode(CrossServerProtocol.Type.DO_LOGIN, player));
    }

    @Override
    public void messagePlayer(String player, String message) {
        send(player, BridgeCodec.encode(CrossServerProtocol.Type.MSG_PLAYER, player, message));
    }

    @Override
    public void titlePlayer(String player, String mainTitle, String subTitle) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null) {
            Title title = proxy.createTitle()
                    .title(new TextComponent(mainTitle == null ? "" : mainTitle))
                    .subTitle(new TextComponent(subTitle == null ? "" : subTitle));
            p.sendTitle(title);
        }
    }

    private void send(String player, byte[] data) {
        ProxiedPlayer p = proxy.getPlayer(player);
        if (p != null && p.getServer() != null) {
            p.getServer().sendData(channel, data);
        }
    }
}
