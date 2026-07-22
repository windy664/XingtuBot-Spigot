package org.windy.xingtubot.ext.xtgroup.reply;

import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.MenuEntry;
import org.windy.xingtubot.common.handler.BotMessageHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自定义问答（replies.yml）。
 * 包装 {@link CustomReplyService}，catch-all 型，priority=80（命令之后、群服互联之前）。
 *
 * <p>帮助菜单从 replies.yml 的 {@code menu} 部分读取（按分类），同时合并
 * replies 中 {@code menu: true} 的条目到「💬 自定义」分类。
 */
public class CustomReplyHandler implements BotMessageHandler {

    private final CustomReplyService customReply;

    public CustomReplyHandler(CustomReplyService customReply) {
        this.customReply = customReply;
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        return customReply != null && customReply.canHandle(event);
    }

    /**
     * 自定义问答是服主显式配置的触发词（在线/状态/规则…），理应在群里直接发就能触发，
     * 无需先 @机器人——与白名单的「绑定/登录」免@一致。
     * 故放开 mention 门控：群里发触发词也能回复。
     *
     * <p>不会误抢普通闲聊:只有命中配置的触发词才 handle,未命中仍落到群服互联兜底镜像进游戏
     * （dispatch 首个命中即停)。AI 闲聊则自身门控只认 @消息,不受此影响。
     */
    @Override
    public boolean acceptsWithoutMention() {
        return true;
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        if (customReply != null) {
            customReply.tryHandle(event);
        }
    }

    public CustomReplyService getService() {
        return customReply;
    }

    @Override
    public List<String> triggers() {
        return customReply != null ? customReply.getAllTriggers() : java.util.Collections.emptyList();
    }

    @Override
    public List<MenuEntry> menuEntries() {
        if (customReply == null) return java.util.Collections.emptyList();
        List<MenuEntry> out = new ArrayList<>();

        // 1. menu 部分（replies.yml 里用户定义的完整菜单，按分类）
        for (Map.Entry<String, List<MenuEntry>> cat : customReply.getMenuByCategory().entrySet()) {
            out.addAll(cat.getValue());
        }

        // 2. replies 中 menu=true 的条目（归入「💬 自定义」）
        for (CustomReply r : customReply.getMenuEntries()) {
            String label = r.name != null && !r.name.isEmpty() ? r.name : r.trigger;
            out.add(new MenuEntry(r.trigger, label, "💬 自定义", false));
        }

        return out;
    }

    @Override
    public String name() {
        return "custom-reply";
    }

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public String category() {
        return "💬 自定义";
    }
}
