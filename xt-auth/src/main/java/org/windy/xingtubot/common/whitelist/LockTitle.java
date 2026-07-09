package org.windy.xingtubot.common.whitelist;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleSubtitle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleText;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes;
import net.kyori.adventure.text.Component;

/**
 * 三端通用的 packetevents title（Object player）。与 {@link LockBossBar} 一样统一走 packetevents，
 * 免各平台各写一套原生 title API，后续维护/抽象只需改这一处。
 *
 * <p>文案按首个 {@code " · "} 拆成主/副标题（见 {@link LockPrompt#titleParts}）；§颜色码经 packetevents
 * 自带的 {@link AdventureSerializer} 解析保色（含 1.16+ hex），无需依赖平台的 adventure serializer。
 */
public final class LockTitle {

    private LockTitle() {
    }

    /** 发一次 title（淡入 0 / 停留 4s / 淡出 0.5s；提醒每 3s 刷新，停留跨过间隔保持常驻观感）。 */
    public static void send(Object player, String legacyText) {
        if (player == null || !QrMapSender.available()) return;
        String[] parts = LockPrompt.titleParts(legacyText);
        Component main = AdventureSerializer.fromLegacyFormat(parts[0]);
        Component sub = AdventureSerializer.fromLegacyFormat(parts[1]);
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerSetTitleTimes(0, 80, 10));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerSetTitleText(main));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerSetTitleSubtitle(sub));
        } catch (Throwable ignored) {
        }
    }
}
