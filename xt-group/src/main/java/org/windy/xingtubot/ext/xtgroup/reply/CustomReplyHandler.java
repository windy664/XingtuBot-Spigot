package org.windy.xingtubot.ext.xtgroup.reply;

import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;

/**
 * 自定义问答（replies.yml）。
 * 包装 {@link CustomReplyService}，触发词型 handler，priority=80（命令之后、群服互联之前）。
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
        // 必须用传入 message（可能是 nonce 探测假消息）：探测据此识别本 handler 为触发词型，
        // 命中触发词的消息会被当命令处理并跳过 AI observer，避免问答 + AI 双回复。
        return customReply != null && customReply.canHandle(message);
    }

    /**
     * 自定义问答是服主显式配置的触发词（在线/状态/规则…），理应在群里直接发就能触发，
     * 无需先 @机器人——与白名单的「绑定/登录」免@一致。
     * 故放开 mention 门控：群里发触发词也能回复。
     *
     * <p>不会误抢普通闲聊：只有命中配置的触发词才 handle，未命中仍落到群服互联兜底镜像进游戏
     * （dispatch 首个命中即停）。命中触发词的消息经 nonce 探测被认定为命令消息，
     * AI observer 直接跳过，不会出现问答 + AI 双回复。
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
