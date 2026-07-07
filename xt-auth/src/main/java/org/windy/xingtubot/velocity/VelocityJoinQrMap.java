package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.util.QrChat;
import org.windy.xingtubot.common.util.QrMatrix;

import java.util.Optional;

/**
 * Velocity 主导下的「加群二维码地图」：代理端直接给客户端发地图包（不经子服、不碰背包）。
 *
 * <p>本类由「白名单」附属插件（xt-auth）拥有：在 {@code AuthVelocityPlugin} 里经核心
 * {@code VelocityBridge.setOnUnboundJoin} 注册回调；群号/链接读 xt-auth 自己的 config。
 *
 * <p>只负责决策 + QR 生成 + 回退；真正的发包隔离在 {@link VelocityMapPacketSender}
 * （直接 import PacketEvents），通过 {@code available()} 守卫懒加载，没装 PacketEvents 时不加载。
 */
public final class VelocityJoinQrMap {

    private VelocityJoinQrMap() {
    }

    /** 未绑定玩家进服时调：发加群二维码地图；没装 PacketEvents 或失败则回退聊天提示。 */
    public static void giveIfEnabled(ProxyServer proxy, BotConfig config, String playerName) {
        if (config == null || playerName == null) return;
        if (!config.getBoolean("group-qr-enable", true)) return;

        String url = config.getString("group-join-url", "").trim();
        String group = config.getString("qq-group", "").trim();
        String content = !url.isEmpty() ? url : group;
        if (content.isEmpty()) return; // 群号和链接都没配，不发

        Optional<Player> op = proxy.getPlayer(playerName);
        if (!op.isPresent()) return;
        Player player = op.get();

        // 样式：chat=聊天二维码(默认,零依赖) / map=代理端发包地图(需PacketEvents) / both=都发
        String style = config.getString("group-qr-style", "chat").trim().toLowerCase();
        boolean wantMap = style.equals("map") || style.equals("both");
        boolean wantChat = style.equals("chat") || style.equals("both");
        if (!wantMap && !wantChat) wantChat = true;

        boolean[][] qr = QrMatrix.encode(content);
        if (qr == null) return;

        boolean mapSent = false;
        if (wantMap && VelocityMapPacketSender.available()) {
            mapSent = VelocityMapPacketSender.send(player, qr);
        }

        if (wantChat) {
            sendChatQr(player, qr, group, url); // 聊天二维码：一行 + 悬停展开（自带群号）
        } else if (mapSent) {
            player.sendMessage(legacy("§a已发送加群二维码地图，§f手持查看并用手机 QQ 扫码加群"
                    + (group.isEmpty() ? "" : "§7（群号 " + group + "）")));
        } else {
            // map 模式但没装 PacketEvents → 聊天提示群号 + 链接
            StringBuilder sb = new StringBuilder("§e加入 QQ 群：");
            if (!group.isEmpty()) sb.append("§b群号 ").append(group).append(" ");
            if (!url.isEmpty()) sb.append("§f").append(url);
            player.sendMessage(legacy(sb.toString()));
        }
    }

    /** 聊天二维码：发一行可悬停消息，hover 展开半块字符二维码（特别小）；有链接则可点击打开。 */
    private static void sendChatQr(Player player, boolean[][] qr, String group, String url) {
        Component qrComp = legacy(QrChat.renderJoined(qr));
        String header = "§a[加群二维码] §7← 悬停查看"
                + (group.isEmpty() ? "" : " §f群号 " + group);
        Component line = legacy(header).hoverEvent(HoverEvent.showText(qrComp));
        if (!url.isEmpty()) {
            line = line.clickEvent(ClickEvent.openUrl(url));
        }
        player.sendMessage(line);
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }
}
