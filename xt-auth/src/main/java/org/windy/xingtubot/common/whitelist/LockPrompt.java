package org.windy.xingtubot.common.whitelist;

/**
 * 三端通用的锁定期引导文案（单一来源）。
 *
 * <p>Bukkit / Velocity / BungeeCord 的登录锁提醒都从这里取文案，保证三端一致、改一处生效。
 * 具体渲染（Velocity=adventure bossbar / Bukkit=原生 BossBar / BungeeCord=packetevents bossbar）
 * 由各平台自理，本类只提供 legacy(§) 文案与「是否已绑定」的颜色判定。
 */
public final class LockPrompt {

    private LockPrompt() {
    }

    /**
     * 按玩家当前阶段返回引导文案（含 §-颜色码）。
     *
     * @param bound       是否已绑定（等群里点「登录」阶段）
     * @param awaitingQQ  是否还在等玩家输入 QQ 号（未声明 QQ）
     */
    public static String text(boolean bound, boolean awaitingQQ) {
        if (bound) {
            return LockMessages.needLogin();
        }
        if (!awaitingQQ) {
            // 已声明 QQ、展示加群二维码地图的阶段
            return LockMessages.needBind();
        }
        return LockMessages.awaitQq();
    }

    /** 已绑定阶段用「积极色」（绿），未完成阶段用「进行色」（黄）。各平台据此映射自己的 bar 颜色。 */
    public static boolean positive(boolean bound) {
        return bound;
    }

    /**
     * 是否处于「展示加群二维码地图」阶段（已声明 QQ、未绑定）。
     * <p>只有此阶段用 bossbar（常驻、不挡玩家看手里的地图）；其余阶段用 title 即可。
     */
    public static boolean isQrPhase(boolean bound, boolean awaitingQQ) {
        return !bound && !awaitingQQ;
    }

    /**
     * 把文案拆成 title 的主/副标题；优先按换行拆，其次按首个 {@code " · "} 拆；无分隔符则副标题为空。
     * @return {@code [main, sub]}
     */
    public static String[] titleParts(String text) {
        if (text == null) return new String[]{"", ""};
        int nl = text.indexOf('\n');
        if (nl >= 0) {
            String main = text.substring(0, nl);
            String sub = text.substring(nl + 1);
            if (main.endsWith("\r")) main = main.substring(0, main.length() - 1);
            if (sub.startsWith("\r")) sub = sub.substring(1);
            return new String[]{main, sub};
        }
        int i = text.indexOf(" · ");
        if (i < 0) return new String[]{text, ""};
        return new String[]{text.substring(0, i), text.substring(i + 3)};
    }
}
