package org.windy.xingtubot.common.module.capability;

/**
 * 平台能力：把机器人主动发到群的文本回显给在线玩家。
 *
 * <p>群服互联（xt-chatlink）开启时自动注册。命令回复的回显已由
 * {@code HandlerRegistry.setGameEcho} 统一处理；本能力供
 * <b>非命令回复</b>的主动消息使用。消费方（xt-github/xt-modquery）
 * 应在<b>使用时</b>惰性 {@code getService}，为 null 即不回显（避免与 xt-chatlink 的加载顺序耦合）。
 */
public interface GameEcho {
    void echo(String text);
}
