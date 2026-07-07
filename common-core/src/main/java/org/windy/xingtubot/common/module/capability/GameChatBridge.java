package org.windy.xingtubot.common.module.capability;

import org.windy.xingtubot.common.event.BotMessageEvent;

/**
 * 平台能力：把 QQ 群消息广播进游戏（群服互联的「QQ→游戏」方向）。
 *
 * <p>由平台侧实现并注册；module-chatlink 经此能力注册 {@code GroupChatHandler}，自身保持平台中立。
 * 实现负责解析发送者昵称、切回主线程、拼回复按钮等平台细节：
 * <ul>
 *   <li>Spigot：{@code GuildMessageListener.broadcastToGame}。</li>
 *   <li>Velocity：解析昵称后调 {@code GroupChatLink.onGroupMessage}。</li>
 * </ul>
 *
 * <p>反方向「游戏→QQ」深度耦合平台聊天事件（Bukkit/Velocity 监听器），与白名单锁同属
 * 平台原生范畴，仍由平台各自实现，不在本能力内。
 */
public interface GameChatBridge {

    /**
     * 把一条（已剥前缀的）群消息广播给在线玩家。
     *
     * @param event   原始群消息事件（含 formId/username，供解析绑定玩家名/拼回复命令）
     * @param content 已剥掉触发前缀的正文
     */
    void broadcastToGame(BotMessageEvent event, String content);
}
