package org.windy.xingtubot.ext.xtgroup.reply;

import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;

/**
 * 自定义问答（replies.yml）。
 * 包装 {@link CustomReplyService}，catch-all 型，priority=80（命令之后、群服互联之前）。
 *
 * <p>帮助菜单由核心 {@code MenuHandler} 统一渲染。
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
    public java.util.List<String> triggers() {
        return customReply != null ? customReply.getAllTriggers() : java.util.Collections.emptyList();
    }

    @Override
    public String name() {
        return "custom-reply";
    }

    @Override
    public int priority() {
        return 80;
    }

    // category 自动继承模块 displayName（"💬 群功能"）
}
