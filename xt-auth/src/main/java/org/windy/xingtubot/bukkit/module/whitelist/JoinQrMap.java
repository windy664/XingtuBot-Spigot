package org.windy.xingtubot.bukkit.module.whitelist;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.windy.xingtubot.common.util.QrChat;
import org.windy.xingtubot.common.util.QrMatrix;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 加群二维码地图（纯 Bukkit / local 模式）：未绑定玩家进服时，把画着「加群二维码」的地图
 * <b>纯发包</b>给客户端（不碰真实背包），扫码即可加 QQ 群。
 *
 * <p>发包走 ProtocolLib（{@link MapPacketSender}）；没装 ProtocolLib 则<b>不发地图</b>，
 * 回退到聊天提示群号 + 链接（绝不往玩家真实背包塞物品）。Velocity 主导模式由代理端 PacketEvents
 * 发包，子服不参与（见 SpigotBridge）。群号/加群链接从子服 config 读。
 *
 * <p><b>跨版本：</b>编译期是 spigot-api 1.12.2，但常运行在更高版本（Youer/Arclight 等）。
 * 地图物品 1.12 是 {@code MAP}、1.13+ 是 {@code FILLED_MAP}，地图 ID 由 short 变 int，
 * {@code MapMeta.setMapView} 仅 1.13.2+——故物品创建走反射兼容，画布渲染用稳定 API。
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

    /** 渲染地图并纯发包（ProtocolLib）；ProtocolLib 不在场返回 false。 */
    private static boolean sendMap(Plugin plugin, Player player, boolean[][] qr,
                                   String displayName, List<String> lore) {
        if (!MapPacketSender.available()) return false;

        MapView view = Bukkit.createMap(player.getWorld());
        // 清掉默认渲染器（地形），只留二维码
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(r);
        }
        view.addRenderer(new QrRenderer(qr));

        ItemStack item = mapItem(view);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        // 纯发包：物品只在客户端当前手持槽显示，服务端真实背包不动
        int held = player.getInventory().getHeldItemSlot();
        return MapPacketSender.sendVirtual(player, view, item, held);
    }

    /** 反射兼容创建地图物品：1.13+ 用 FILLED_MAP+setMapView，1.12 用 MAP+durability(mapId)。 */
    private static ItemStack mapItem(MapView view) {
        Material mat = mapMaterial();
        ItemStack item = new ItemStack(mat);

        // 1.13.2+：MapMeta.setMapView
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            try {
                Method setMapView = meta.getClass().getMethod("setMapView", MapView.class);
                setMapView.invoke(meta, view);
                item.setItemMeta(meta);
                return item;
            } catch (Throwable ignored) {
                // 老版本没有 setMapView，走 durability
            }
        }
        // 1.12.2：地图 ID 写在 durability
        item.setDurability((short) mapId(view));
        return item;
    }

    /** FILLED_MAP（1.13+）优先，回退 MAP（1.12）。 */
    private static Material mapMaterial() {
        Material m = matByName("FILLED_MAP");
        if (m == null) m = matByName("MAP");
        return m != null ? m : Material.PAPER; // 极端兜底（理论不会到）
    }

    private static Material matByName(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** MapView.getId()：1.12 返回 short、1.13+ 返回 int，统一反射取 Number。 */
    private static int mapId(MapView view) {
        try {
            Object id = MapView.class.getMethod("getId").invoke(view);
            return ((Number) id).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 把二维码矩阵画到 128×128 地图：居中放大、黑点画黑、底白。只画一次。 */
    private static final class QrRenderer extends MapRenderer {
        private final boolean[][] qr;
        private boolean drawn = false;

        QrRenderer(boolean[][] qr) {
            this.qr = qr;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (drawn) return; // 静态图，画一次即可，避免每 tick 重绘
            drawn = true;

            byte white = MapPalette.matchColor(255, 255, 255);
            byte black = MapPalette.matchColor(0, 0, 0);
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    canvas.setPixel(x, y, white);
                }
            }

            int n = qr.length;
            if (n == 0) return;
            int scale = Math.max(1, 128 / n);   // 每个二维码模块放大成 scale×scale 像素
            int size = n * scale;
            int off = (128 - size) / 2;          // 居中

            for (int r = 0; r < n; r++) {
                boolean[] row = qr[r];
                for (int c = 0; c < row.length; c++) {
                    if (!row[c]) continue;
                    int px = off + c * scale;
                    int py = off + r * scale;
                    for (int dx = 0; dx < scale; dx++) {
                        for (int dy = 0; dy < scale; dy++) {
                            int x = px + dx, y = py + dy;
                            if (x >= 0 && x < 128 && y >= 0 && y < 128) {
                                canvas.setPixel(x, y, black);
                            }
                        }
                    }
                }
            }
        }
    }
}
