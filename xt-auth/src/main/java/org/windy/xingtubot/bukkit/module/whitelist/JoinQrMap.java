package org.windy.xingtubot.bukkit.module.whitelist;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.util.QrMatrix;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.common.whitelist.QrMapSender;

/**
 * 加群二维码地图（纯 Bukkit / local 模式）：把画着「加群二维码」的地图<b>纯发包</b>给客户端
 * 并强制玩家手持，扫码即可加 QQ 群（不碰真实背包）。
 *
 * <p>只走<b>地图</b>：聊天渲染不出可扫的二维码、已无意义。发包走三端共享 {@link QrMapSender}
 * （PacketEvents 已内置进 jar）。群号/加群链接从子服 config 读，编码一次缓存复用（重复调用不重算）。
 */
public final class JoinQrMap {

    // 二维码内容随 config 固定，编码一次缓存复用（重复提醒每隔几秒可能触发，绝不能每次重算）。
    private static volatile boolean qrInit;
    private static volatile boolean[][] qrMatrix; // null = 未启用/无群号无链接/编码失败
    private static volatile String qrGroup = "";  // 群号
    private static volatile String qrUrl = "";    // 加群链接

    private JoinQrMap() {
    }

    /** 编码二维码并缓存。线程安全、只算一次；内容由 config 决定。 */
    private static void initQr(Plugin plugin) {
        if (qrInit) return;
        synchronized (JoinQrMap.class) {
            if (qrInit) return;
            try {
                if (!plugin.getConfig().getBoolean("group-qr-enable", true)) return;
                String joinUrl = plugin.getConfig().getString("group-join-url", "").trim();
                String group = plugin.getConfig().getString("qq-group", "").trim();
                String content = !joinUrl.isEmpty() ? joinUrl : group;
                if (content.isEmpty()) return;

                boolean[][] qr = QrMatrix.encode(content);
                if (qr == null) {
                    plugin.getLogger().warning("[加群二维码] 生成失败，已跳过");
                    return;
                }
                qrMatrix = qr;
                qrUrl = joinUrl;
                qrGroup = group;
            } finally {
                qrInit = true;
            }
        }
    }

    /** 把加群二维码作为地图物品强制放到玩家手上（未启用 / 无群号无链接则静默跳过）。 */
    public static void giveMap(Plugin plugin, Player player) {
        if (player == null) return;
        if (!plugin.getConfig().getBoolean("group-qr-enable", true)) return;
        initQr(plugin);
        if (qrMatrix == null) return;

        if (QrMapSender.available() && QrMapSender.send(player, qrMatrix)) {
            return; // 地图已强制放到手上，扫码引导由 bossbar 展示
        }

        // 兜底：packetevents 运行期不可用（已内置，理论上不该发生）→ 纯文字群号/链接
        player.sendMessage(LockMessages.qrFallback(qrGroup, qrUrl));
    }

    /** 解锁 / 绑定成功后刷新客户端，把纯发包的虚拟地图覆盖回真实背包显示。 */
    public static void cleanup(Player player) {
        if (player == null) return;
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
        }
    }
}
