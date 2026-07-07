package org.windy.xingtubot.common.reply;

import org.windy.xingtubot.common.event.BotMessageEvent;

/**
 * 占位符解析接口：把模板里的 {online}/{sender}/{date}… 以及（二期）PAPI 的 %xxx%
 * 替换成实际值。由平台端实现（Velocity 能算在线人数、子服等）。
 *
 * <p>群消息场景没有玩家上下文，故 PAPI 仅在「发送者已绑定且在线」时可跨服解析（二期）；
 * 内置占位符不依赖玩家、不依赖 PAPI，群里随便用。
 */
public interface PlaceholderResolver {

    /**
     * 把 text 里的占位符替换为实际值，结果通过回调返回（因 PAPI 解析可能需跨服异步）。
     * 内置占位符同步替换；含 PAPI %xxx% 且发送者绑定+在线时异步解析，否则原样。
     * 回调一定会被调用一次。
     */
    void resolve(String text, BotMessageEvent event, java.util.function.Consumer<String> callback);
}
