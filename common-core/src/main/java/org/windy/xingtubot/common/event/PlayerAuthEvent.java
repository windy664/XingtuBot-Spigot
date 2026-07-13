package org.windy.xingtubot.common.event;

/**
 * 玩家认证事件：VelocityBridge 在玩家进服时根据绑定/登录状态发布，
 * 由 xt-auth 订阅后用 DirectAuthAdapter 执行具体通知/解锁操作。
 *
 * <p>Bridge 只负责判定状态和发事件，不关心具体怎么通知玩家。
 */
public class PlayerAuthEvent {

    public enum Action {
        /** 未绑定：需要输入 QQ 号开始绑定流程。 */
        UNBOUND,
        /** 已声明 QQ 未绑定：需要在群里发「绑定」完成验证。 */
        PENDING,
        /** 已绑定需登录：需要在群里点「登录」按钮。 */
        NEED_LOGIN,
        /** 本会话已登录（跨子服切换）：静默解锁。 */
        ALREADY_LOGGED,
        /** IP 自动登录：同设备信任期内免密登录。 */
        AUTO_LOGIN
    }

    private final String player;
    private final Action action;

    public PlayerAuthEvent(String player, Action action) {
        this.player = player;
        this.action = action;
    }

    public String getPlayer() { return player; }
    public Action getAction() { return action; }
}
