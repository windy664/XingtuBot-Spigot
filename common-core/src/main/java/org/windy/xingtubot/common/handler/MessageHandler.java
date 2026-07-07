package org.windy.xingtubot.common.handler;

import org.windy.xingtubot.common.event.BotMessageEvent;

/**
 * 统一的消息处理器接口。
 * 所有群消息功能（命令型 / 非命令型）都实现此接口，由 {@link HandlerRegistry} 统一注册和分发。
 *
 * <p>与 {@link org.windy.xingtubot.common.command.GroupCommand} 的区别：
 * GroupCommand 仅覆盖「前缀匹配」型命令；MessageHandler 还支持 catch-all、
 * 优先级排序、生命周期回调。GroupCommand 通过适配器自动兼容。
 */
public interface MessageHandler {

    /** 是否由本处理器处理这条消息。 */
    boolean matches(String message, BotMessageEvent event);

    /**
     * 处理消息并回复。已在异步线程中调用，可直接做网络请求。
     */
    void handle(String message, BotMessageEvent event);

    /** 处理器名称（日志/调试用）。 */
    String name();

    /** 匹配优先级，越小越先匹配。默认 100。 */
    default int priority() {
        return 100;
    }

    /** 是否仅管理员可用。默认 false。 */
    default boolean adminOnly() {
        return false;
    }

    /**
     * 即使在 {@code listen-mode: mention}（仅响应@机器人）下，本处理器是否也接收
     * <b>非@</b>的群消息（{@code GROUP_MESSAGE_CREATE}）。默认 false。
     *
     * <p>白名单/登录这类「玩家未必知道要@机器人」的关键流程应返回 true，
     * 让玩家直接发「绑定」「登录」即可被处理，无需先@。其余命令保持默认
     * （仍需@），避免机器人对群里每条闲聊都响应。
     *
     * <p>注意：这只放开本插件内部的 mention 门控；前提是 QQ 开放平台/网关确实把
     * 非@消息投递了过来（需相应的消息接收权限/intent），否则消息根本不会到达。
     */
    default boolean acceptsWithoutMention() {
        return false;
    }

    /**
     * 针对具体消息的管理员判定。默认回退到 {@link #adminOnly()}。
     * 自定义命令等「同一 handler 内不同条目鉴权不同」的场景重写此方法。
     */
    default boolean adminFor(String message) {
        return adminOnly();
    }

    /**
     * 额外的命令触发词（如自定义问答/命令的 trigger）。供 AI 等排除已注册前缀，
     * 避免对已有命令的消息重复响应。默认空。
     */
    default java.util.List<String> triggers() {
        return java.util.Collections.emptyList();
    }

    /**
     * 动态菜单条目（如 replies.yml 中 menu=true 的项）。由 {@link HandlerRegistry#buildMenu}
     * 统一收集。返回 core 中立的 {@link MenuEntry}，不暴露具体功能类型。默认空。
     */
    default java.util.List<MenuEntry> menuEntries() {
        return java.util.Collections.emptyList();
    }

    /**
     * 帮助菜单里的用法说明。返回 null 则不进菜单（适合内部/隐藏处理器）。
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
     * 默认 "" 表示不分组。buildMenu 会按此字段分组显示。
     */
    default String category() {
        return "";
    }

    /** 生命周期：注册后、首次分发前调用。可选，用于初始化依赖。 */
    default void init(HandlerContext ctx) {
    }

    /** 生命周期：插件关闭时调用。可选，用于释放资源。 */
    default void shutdown() {
    }
}
