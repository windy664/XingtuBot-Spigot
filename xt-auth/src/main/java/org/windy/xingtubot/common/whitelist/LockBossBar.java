package org.windy.xingtubot.common.whitelist;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三端通用的锁定期 bossbar：统一用 PacketEvents 发 bossbar 包，一套代码跑 Bukkit / Velocity / BungeeCord。
 *
 * <p>PacketEvents 的 {@code getPlayerManager().sendPacket(Object, wrapper)} 接受任意平台 player 对象，
 * 故三端共用；BungeeCord 本无原生 bossbar，Velocity/Bukkit 也复用这套，免三份实现。
 *
 * <p>标题用 PacketEvents 内置（未 relocate）的 adventure {@link Component}；legacy serializer 是 relocate 版、
 * 运行期不保证，故 strip 掉 §颜色码用纯文本，阶段颜色由 bar color 表达（绿=已绑定 / 黄=未完成）。
 *
 * <p>每个 LockManager 持一个实例，按玩家名维护 bar 的 UUID：{@link #set} 幂等（首次 ADD、后续 UPDATE），
 * {@link #clear} 发 REMOVE 并清状态。PacketEvents 未就绪时静默跳过（由 {@link QrMapSender#available()} 守卫）。
 */
public final class LockBossBar {

    private final Map<String, UUID> ids = new ConcurrentHashMap<>();

    /** 显示/更新玩家 bossbar（幂等）。{@code player} 为平台原生对象；{@code positive}=true 用绿色。 */
    public void set(Object player, String name, String legacyText, boolean positive) {
        if (player == null || name == null || !QrMapSender.available()) return;
        String key = name.toLowerCase(Locale.ROOT);
        Component title = Component.text(strip(legacyText));
        BossBar.Color color = positive ? BossBar.Color.GREEN : BossBar.Color.YELLOW;
        UUID id = ids.get(key);
        if (id == null) {
            id = UUID.randomUUID();
            ids.put(key, id);
            WrapperPlayServerBossBar add = new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.ADD);
            add.setTitle(title);
            add.setColor(color);
            add.setOverlay(BossBar.Overlay.PROGRESS);
            add.setHealth(1.0f);
            add.setFlags(EnumSet.noneOf(BossBar.Flag.class));
            send(player, add);
        } else {
            WrapperPlayServerBossBar upTitle =
                    new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_TITLE);
            upTitle.setTitle(title);
            send(player, upTitle);
            WrapperPlayServerBossBar upStyle =
                    new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.UPDATE_STYLE);
            upStyle.setColor(color);
            upStyle.setOverlay(BossBar.Overlay.PROGRESS);
            send(player, upStyle);
        }
    }

    /** 隐藏并清除玩家 bossbar。{@code player} 可为 null（离线），此时仅清状态不发包。 */
    public void clear(Object player, String name) {
        if (name == null) return;
        UUID id = ids.remove(name.toLowerCase(Locale.ROOT));
        if (id == null || player == null || !QrMapSender.available()) return;
        WrapperPlayServerBossBar rem = new WrapperPlayServerBossBar(id, WrapperPlayServerBossBar.Action.REMOVE);
        send(player, rem);
    }

    private static void send(Object player, WrapperPlayServerBossBar w) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, w);
        } catch (Throwable ignored) {
        }
    }

    /** 去掉 §x 颜色/格式码，得纯文本（bossbar 标题用）。 */
    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("(?i)§[0-9A-FK-OR]", "");
    }
}
