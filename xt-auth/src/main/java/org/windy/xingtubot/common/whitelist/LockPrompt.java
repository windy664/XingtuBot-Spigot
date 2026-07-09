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
}
