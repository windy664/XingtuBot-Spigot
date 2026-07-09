package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.util.QrMatrix;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.common.whitelist.QrMapSender;

/**
 * BungeeCord 端加群二维码（由「白名单」附属插件 xt-auth 拥有）。
 *
 * <p>与 Velocity 一致：只走<b>地图物品</b>——PacketEvents 已内置进 jar，代理端同样能发地图包 +
 * 强制手持；聊天渲染不出可扫的二维码、已无意义。群号/链接读 xt-auth 自己的 config。
 * 由 {@code AuthBungeeCordPlugin} 经核心 {@code BungeeCordBridge.setOnUnboundJoin} 注册。
 */
public class BungeeCordJoinQrMap {

    public static void giveIfEnabled(ProxyServer proxy, BotConfig config, String playerName) {
        if (config == null || playerName == null) return;
        if (!config.getBoolean("group-qr-enable", true)) return;

        String url = config.getString("group-join-url", "").trim();
        String group = config.getString("qq-group", "").trim();
        String content = !url.isEmpty() ? url : group;
        if (content.isEmpty()) return; // 群号和链接都没配，不发

        ProxiedPlayer player = proxy.getPlayer(playerName);
        if (player == null || !player.isConnected()) return;

        boolean[][] qr = QrMatrix.encode(content);
        if (qr == null) return;

        // 只走地图物品：三端共用 QrMapSender（ProxiedPlayer 以 Object 传入）
        if (QrMapSender.available() && QrMapSender.send(player, qr)) {
            return; // 成功：地图已强制放到玩家手上，扫码引导由 bossbar 展示
        }

        // 兜底：packetevents 运行期不可用（已内置，理论上不该发生）→ 纯文字群号/链接
        player.sendMessage(TextComponent.fromLegacyText(LockMessages.qrFallback(group, url)));
    }
}
