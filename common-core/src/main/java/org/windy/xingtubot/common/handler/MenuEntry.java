package org.windy.xingtubot.common.handler;

/**
 * 帮助菜单的一条动态条目。core 自有的中立类型，不依赖任何具体功能。
 * 供 {@link BotMessageHandler#menuEntries()} 返回，由 {@link HandlerRegistry#buildMenu} 统一收集渲染。
 */
public final class MenuEntry {

    /** 触发词（菜单里显示的命令）。 */
    public final String trigger;

    /** 一句话描述/标签。 */
    public final String label;

    /** 菜单分类（如 "🎮 娱乐"）。空字符串表示用 handler 的 category()。 */
    public final String category;

    /** 是否仅超管可见。 */
    public final boolean adminOnly;

    public MenuEntry(String trigger, String label) {
        this(trigger, label, "", false);
    }

    public MenuEntry(String trigger, String label, String category, boolean adminOnly) {
        this.trigger = trigger;
        this.label = label;
        this.category = category;
        this.adminOnly = adminOnly;
    }
}
