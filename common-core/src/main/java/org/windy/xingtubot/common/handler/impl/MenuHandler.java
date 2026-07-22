package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.HandlerRegistry;
import org.windy.xingtubot.common.handler.PermissionChecker;

/**
 * 内置菜单命令：触发词「菜单 / 帮助 / help」→ 渲染全部已注册命令清单（按模块分类 + 权限分组）。
 *
 * <p>菜单内容由 {@link HandlerRegistry#buildMenu} 动态生成，
 * 新增/卸载任何功能模块都会自动反映，无需手动维护。
 *
 * <p>回复走 Markdown + 按钮键盘（无参一键发、带参填草稿），与 QQ 官方卡片审美一致。
 */
public class MenuHandler implements BotMessageHandler {

    private final HandlerRegistry registry;
    private PermissionChecker permission;

    public MenuHandler(HandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void init(HandlerContext ctx) {
        this.permission = ctx.getPermission();
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        String m = message == null ? "" : message.trim().toLowerCase();
        return m.equals("菜单") || m.equals("帮助") || m.equals("help")
                || m.equals("功能") || m.equals("指令");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        boolean isAdmin = permission != null && permission.isAdmin(event.getSenderId());
        String markdown = registry.buildMenu(isAdmin);
        String keyboard = registry.buildMenuKeyboard(isAdmin);
        if (keyboard != null) {
            event.replyKeyboard(markdown, keyboard);
        } else {
            event.replyMarkdown(markdown, null);
        }
    }

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public int priority() {
        return 5; // 最高优先级：纯关键词命令，先于一切 handler
    }

    @Override
    public boolean acceptsWithoutMention() {
        return true; // 菜单命令免 @
    }

    @Override
    public String usage() {
        return "菜单";
    }

    @Override
    public String description() {
        return "查看全部功能与指令";
    }
}
