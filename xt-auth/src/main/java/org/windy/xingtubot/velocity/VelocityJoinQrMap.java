package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.util.QrMatrix;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.common.whitelist.QrMapSender;

import java.util.Optional;

/**
 * Velocity 主导下的「加群二维码地图」：代理端直接给客户端发地图包（不经子服、不碰背包），
 * 并强制玩家手持，让二维码直接显示在手上。
 *
 * <p>只走 <b>map</b>：聊天渲染不出可扫的二维码，已无意义。真正的发包走三端共享的
 * {@link QrMapSender}（直接 import PacketEvents），经 {@code available()} 守卫懒加载。
 *
 * <p>玩家侧的文字引导（"手持地图扫码加群"）由登录锁 {@link VelocityPlayerLock} 的 bossbar 统一展示，
 * 本类只负责把地图放到玩家手上。
 */
public final class VelocityJoinQrMap {

    private VelocityJoinQrMap() {
    }

    /** 未绑定玩家登记 QQ 后调：把加群二维码作为地图物品强制放到玩家手上。 */
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

        boolean[][] qr = QrMatrix.encode(content);
        if (qr == null) return;

        // 只走地图物品：chat 渲染不出可扫的二维码，无意义。三端共用 QrMapSender。
        if (QrMapSender.available() && QrMapSender.send(player, qr)) {
            return; // 成功：地图已强制放到玩家手上，扫码引导由 bossbar 统一展示
        }

        // 兜底：packetevents 运行期不可用（已内置进 jar，理论上不该发生）→ 给纯文字群号/链接。
        // 不再退回聊天二维码（渲染不出可扫的码，无意义）。
        player.sendMessage(legacy(LockMessages.qrFallback(group, url)));
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }
}
