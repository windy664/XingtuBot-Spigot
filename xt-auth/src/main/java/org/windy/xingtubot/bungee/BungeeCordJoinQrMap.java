package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.windy.xingtubot.common.config.BotConfig;

/**
 * BungeeCord 端加群二维码（由「白名单」附属插件 xt-auth 拥有）。
 *
 * <p>代理端无法直接发地图包，统一回退为聊天栏提示；群号/链接读 xt-auth 自己的 config。
 * 由 {@code AuthBungeeCordPlugin} 经核心 {@code BungeeCordBridge.setOnUnboundJoin} 注册。
 */
public class BungeeCordJoinQrMap {

    public static void giveIfEnabled(ProxyServer proxy, BotConfig config, String playerName) {
        if (!config.getBoolean("group-qr-enable", true)) return;
        String joinUrl = config.getString("group-join-url", "").trim();
        String qqGroup = config.getString("qq-group", "").trim();
        if (joinUrl.isEmpty() && qqGroup.isEmpty()) return;

        ProxiedPlayer player = proxy.getPlayer(playerName);
        if (player == null) return;

        String content = !joinUrl.isEmpty() ? joinUrl : qqGroup;
        String label = !joinUrl.isEmpty() ? "扫码加群" : "群号: " + qqGroup;

        // 聊天栏提示（BungeeCord 不支持地图包）
        player.sendMessage(new TextComponent("§e[加群] §f" + label + " §7" + content));
    }
}
