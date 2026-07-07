package org.windy.xingtubot.common.module.capability;

import java.util.function.Consumer;

/**
 * 平台能力：以指定玩家身份执行一条命令并回传结果（自定义命令的「玩家执行」分支）。
 *
 * <ul>
 *   <li>Spigot：主线程调度，按玩家名取在线玩家 dispatchCommand。</li>
 *   <li>Velocity：通常为 null（玩家在子服，跨服玩家执行未实现）。</li>
 * </ul>
 */
public interface PlayerCommandExecutor {

    /**
     * 以玩家身份执行命令。
     *
     * @param playerName 玩家名
     * @param command    命令（不含前导斜杠语义由实现决定）
     * @param output     结果回调（成功/失败/不在线提示）
     */
    void exec(String playerName, String command, Consumer<String> output);
}
