package org.windy.xingtubot.bukkit.module.whitelist;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.util.QrChat;
import org.windy.xingtubot.common.util.QrMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * 加群二维码地图（纯 Bukkit / local 模式）：未绑定玩家进服时，把画着「加群二维码」的地图
 * <b>纯发包</b>给客户端（不碰真实背包），扫码即可加 QQ 群。
 *
 * <p>发包走 PacketEvents（{@link BukkitMapPacketSender}），内置在 jar 中，无需额外依赖。
 * 群号/加群链接从子服 config 读。
 */
public final class JoinQrMap {

    // 二维码内容随 config 固定，编码一次缓存复用（重复提醒每隔几秒就要发一次，绝不能每次重算）。
    private static volatile boolean qrInit;
    private static volatile boolean[][] qrMatrix; // null = 未启用/无群号无链接/编码失败
    private static volatile String qrHover;       // 聊天 hover 文本（说明 + 二维码 + 说明），null = 不发聊天码
    private static volatile String qrLabel = "";  // 聊天行可见文字（鼠标悬停看码）
    private static volatile String qrUrl = "";    // 加群链接（非空则可点击打开）
    private static volatile String qrGroup = "";  // 群号

    private JoinQrMap() {
    }

    /** 编码二维码并缓存（说明+矩阵+hover 文本）。线程安全、只算一次；内容由 config 决定。 */
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
                // 把「说明 + 二维码 + 怎么做」一并放进 hover，扫码者一眼明白
                StringBuilder h = new StringBuilder("§f手机 QQ 扫码加群");
                if (!group.isEmpty()) h.append(" §7(群号 ").append(group).append(")");
                h.append("\n\n").append(QrChat.renderJoined(qr))
                        .append("\n\n§7进群后，按上方提示完成绑定 / 登录");
                qrHover = h.toString();
                qrLabel = "§a➤ §b[加群二维码]§7 ← 悬停查看" + (joinUrl.isEmpty() ? "" : "（可点击打开）");
            } finally {
                qrInit = true;
            }
        }
    }

    /**
     * 发一条提示消息，并把「加群二维码」<b>捆绑到同一行</b>（鼠标悬停看码、有链接可点开）。
     *
     * <p>专供锁定期的<b>重复提醒</b>调用：每次提醒都重发一次，二维码就永远跟在最新提示后面，
     * 不会像一次性消息那样被聊天冲走、划过去就扫不到。
     *
     * <p>二维码未启用 / 无群号无链接 / 样式为纯 {@code map} 时，退化为普通文本消息（地图是手持持久物，
     * 不需要聊天行兜底）。
     */
    public static void sendWithQr(Plugin plugin, Player player, String legacyMsg) {
        if (player == null) return;
        // 样式：map=只发地图(此处不附聊天码) / chat / both / 未知 → 都附聊天码
        String style = plugin.getConfig().getString("group-qr-style", "chat").trim().toLowerCase();
        boolean wantChat = !style.equals("map");

        initQr(plugin);
        if (!wantChat || qrHover == null) {
            player.sendMessage(legacyMsg);
            return;
        }
        TextComponent line = new TextComponent(TextComponent.fromLegacyText(legacyMsg + "  "));
        TextComponent qr = new TextComponent(TextComponent.fromLegacyText(qrLabel));
        qr.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(qrHover)));
        if (qrUrl != null && !qrUrl.isEmpty()) {
            qr.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, qrUrl));
        }
        line.addExtra(qr);
        player.spigot().sendMessage(line);
    }

    /**
     * 未绑定玩家进服时，按 config 给客户端「纯发包」一张加群二维码<b>地图</b>（手持持久，不碰真实背包）。
     * 仅当 {@code group-qr-style} 含 {@code map}/{@code both} 且装了 ProtocolLib 时发；
     * 聊天二维码由 {@link #sendWithQr} 负责（含重复提醒），这里只管地图。
     */
    public static void giveMapIfEnabled(Plugin plugin, Player player) {
        if (player == null) return;
        if (!plugin.getConfig().getBoolean("group-qr-enable", true)) return;
        String style = plugin.getConfig().getString("group-qr-style", "chat").trim().toLowerCase();
        if (!style.equals("map") && !style.equals("both")) return; // 只有 map/both 才发地图

        initQr(plugin);
        if (qrMatrix == null) return;
        try {
            boolean sent = sendMap(plugin, player, qrMatrix, "§a§l扫码加入 QQ 群", buildLore(qrGroup, qrUrl));
            if (sent) {
                player.sendMessage("§a已发送加群二维码地图，§f手持查看并用手机 QQ 扫码加群"
                        + (qrGroup.isEmpty() ? "" : "§7（群号 " + qrGroup + "）"));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[加群二维码] 地图发包失败: " + t.getMessage());
        }
    }

    /** 解锁/绑定成功后刷新客户端，把纯发包的虚拟地图覆盖回真实背包显示。 */
    public static void cleanup(Player player) {
        if (player == null) return;
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
        }
    }

    private static List<String> buildLore(String group, String joinUrl) {
        List<String> lore = new ArrayList<>();
        if (!group.isEmpty()) lore.add("§7群号: §f" + group);
        lore.add("§7用手机 QQ 扫描这张地图加群");
        if (joinUrl.isEmpty()) {
            lore.add("§8(未配 group-join-url，二维码为群号文本)");
        }
        return lore;
    }

    /** 用 PacketEvents 纯发包地图（内置，无需额外依赖）。 */
    private static boolean sendMap(Plugin plugin, Player player, boolean[][] qr,
                                   String displayName, List<String> lore) {
        return BukkitMapPacketSender.send(player, qr);
    }

}
