package org.windy.xingtubot.common.command;

import org.windy.xingtubot.common.event.BotMessageEvent;

/**
 * 群指令接口：一个独立的群聊功能（天气/运势/图片等）。
 *
 * <p>由 {@link GroupCommandRegistry} 统一分发。实现类只关心「匹配 + 处理」，
 * 不碰平台细节。处理中若需网络请求请自行放异步（注册器会在异步线程调用 handle）。
 */
public interface GroupCommand {

    /** 是否由本指令处理这条消息（通常按前缀/关键词判断）。 */
    boolean matches(String message);

    /**
     * 处理消息并回复。已在异步线程中调用，可直接做网络请求。
     *
     * @param message 去除首尾空格后的完整消息
     * @param event   原始事件，用 event.reply/replyImage/... 回复
     */
    void handle(String message, BotMessageEvent event);

    /** 指令名（日志/帮助用）。 */
    String name();

    /** 是否仅超管可用。默认 false（人人可用）；管理类指令重写为 true。 */
    default boolean adminOnly() {
        return false;
    }

    /**
     * 针对具体消息的管理员判定。默认回退 {@link #adminOnly()}。
     * 自定义命令等「同一指令内不同条目鉴权不同」的场景重写。
     */
    default boolean adminFor(String message) {
        return adminOnly();
    }

    /**
     * 额外触发词（如自定义命令的多个 trigger），供 AI 等排除已注册前缀。默认空。
     */
    default java.util.List<String> triggers() {
        return java.util.Collections.emptyList();
    }

    /**
     * 帮助菜单里的用法说明，如「天气 城市」。返回 null 则不在菜单中显示
     * （适合内部/隐藏指令）。
     */
    default String usage() {
        return null;
    }

    /** 帮助菜单里的一句话描述。 */
    default String description() {
        return "";
    }

    /**
     * 帮助菜单分类标签（如 "🎮 娱乐"、"🔍 模组"、"⚙ 系统"）。
     * 默认 "" 表示不分组。
     */
    default String category() {
        return "";
    }
}
