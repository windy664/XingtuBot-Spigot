package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;

import java.util.function.BiConsumer;

/**
 * 群服互联兜底：以上所有命令都没匹配 = 普通聊天，广播进游戏。
 * priority=100，最后匹配。
 *
 * <p>通过回调注入实际的广播逻辑（Velocity 端用 GroupChatLink，Spigot 端用 ChatreplyModule）。
 *
 * <p>门控统一在此（两端共用）：
 * <ul>
 *   <li>mention 门控已在 {@code HandlerRegistry.dispatch} 顶层统一过滤；</li>
 *   <li>可选 {@code prefix}（config 的 {@code startsWith}）：QQ 群消息需以此前缀开头才转发进游戏，
 *       命中后会自动剥掉前缀；留空=所有（经监听模式过滤的）群消息都转发。</li>
 * </ul>
 */
public class GroupChatHandler implements BotMessageHandler {

    /**
     * 广播回调：(event, message) -> 广播进游戏。
     * 由平台侧注入具体实现。
     */
    private final BiConsumer<BotMessageContext, String> broadcaster;

    /** 群服互联触发前缀；null/空=不需要前缀（catch-all）。 */
    private final String prefix;

    public GroupChatHandler(BiConsumer<BotMessageContext, String> broadcaster) {
        this(broadcaster, null);
    }

    public GroupChatHandler(BiConsumer<BotMessageContext, String> broadcaster, String prefix) {
        this.broadcaster = broadcaster;
        this.prefix = (prefix == null || prefix.trim().isEmpty()) ? null : prefix.trim();
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        if (broadcaster == null) return false;
        if (prefix == null) return true; // catch-all
        return message != null && message.trim().startsWith(prefix);
    }

    /**
     * 群服互联按定义要把<b>群里所有聊天</b>镜像进游戏，而非只镜像 @机器人 的消息，
     * 故主动放开 mention 门控——否则非@群消息会在 {@code HandlerRegistry.dispatch} 顶层被门控，永远传不进游戏。
     *
     * <p>这样可同时满足「AI/命令只在@时响应」+「群服互联镜像全部聊天」两个诉求，二者解耦。
     */
    @Override
    public boolean acceptsWithoutMention() {
        return true;
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String content = message == null ? "" : message.trim();
        if (prefix != null && content.startsWith(prefix)) {
            content = content.substring(prefix.length()).trim();
        }
        // 纯图片消息正文为空但带图——也要转发（广播器会拼 ChatImage 码）；文字、图片都空才丢
        if (content.isEmpty() && event.getImageUrls().isEmpty()) return;
        broadcaster.accept(event, content);
    }

    @Override
    public String name() {
        return "group-chat";
    }

    @Override
    public int priority() {
        return 100;
    }
}
