package org.windy.xingtubot.common.binding;

/**
 * 认证/玩家操作适配接口：屏蔽平台与认证插件差异。
 *
 * <p>Spigot 端用 AuthMe 实现（forceRegister / forceLogin）；Velocity 等其它端可另行实现。
 * 绑定核心逻辑（{@link BindingService}）只依赖本接口，不直接碰 AuthMe / Bukkit。
 */
public interface AuthAdapter {

    /** 玩家是否在线。 */
    boolean isOnline(String player);

    /** 免密码注册（如 AuthMe forceRegister）。玩家须在线。 */
    void register(String player);

    /** 强制登录（如 AuthMe forceLogin）。玩家须在线。 */
    void login(String player);

    /** 给在线玩家发一条游戏内消息。 */
    void messagePlayer(String player, String message);

    /** 给在线玩家显示一个标题/副标题（支持 §颜色码）。subTitle 可为 null/空。 */
    void titlePlayer(String player, String mainTitle, String subTitle);

    /**
     * 带停留时间的标题（单位 tick，20 tick=1 秒）。用于<b>持续提示</b>循环：stay 设得≥刷新间隔、
     * fadeIn 设 0，可让标题不闪烁地常驻。默认实现忽略时间、退化为 {@link #titlePlayer(String, String, String)}。
     */
    default void titlePlayer(String player, String mainTitle, String subTitle,
                             int fadeInTicks, int stayTicks, int fadeOutTicks) {
        titlePlayer(player, mainTitle, subTitle);
    }
}
