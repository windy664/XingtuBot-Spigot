package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.bridge.BridgeCodec;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;

/**
 * Velocity（大脑）侧的 {@link AuthAdapter}：把 AuthMe 操作通过 Plugin Message
 * 派发给玩家<b>当前所在的 Spigot 子服</b>执行。
 *
 * <p>玩家可能在任意子服，这里用 {@code getCurrentServer()} 定位后定向发送。
 */
public class PluginMessageAuthAdapter implements AuthAdapter {

    private final ProxyServer proxy;
    private final ChannelIdentifier channel;

    public PluginMessageAuthAdapter(ProxyServer proxy, ChannelIdentifier channel) {
        this.proxy = proxy;
        this.channel = channel;
    }

    @Override
    public boolean isOnline(String player) {
        return proxy.getPlayer(player).isPresent();
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
        // Velocity 是客户端连接的代理，可直接发 title 包，无需经子服中转。
        proxy.getPlayer(player).ifPresent(p -> p.showTitle(Title.title(
                legacy(mainTitle), legacy(subTitle))));
    }

    @Override
    public void titlePlayer(String player, String mainTitle, String subTitle,
                            int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Title.Times times = Title.Times.of(
                java.time.Duration.ofMillis(fadeInTicks * 50L),
                java.time.Duration.ofMillis(stayTicks * 50L),
                java.time.Duration.ofMillis(fadeOutTicks * 50L));
        proxy.getPlayer(player).ifPresent(p -> p.showTitle(Title.title(
                legacy(mainTitle), legacy(subTitle), times)));
    }

    /** 解析 §颜色码（原来用 Component.text 会把 § 当普通字符显示出来）。 */
    private static Component legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(s == null ? "" : s);
    }

    /** 发给玩家当前所在子服。玩家须在线（这些场景本就如此）。 */
    private void send(String player, byte[] data) {
        proxy.getPlayer(player)
                .flatMap(p -> p.getCurrentServer())
                .ifPresent(sc -> sc.sendPluginMessage(channel, data));
    }
}
