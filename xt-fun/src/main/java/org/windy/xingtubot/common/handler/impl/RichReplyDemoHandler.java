package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.demo.RichReplyDemo;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;

/**
 * 富消息 demo（发「测试」或「/demo」触发）。
 * 包装 {@link RichReplyDemo}，priority=90。
 */
public class RichReplyDemoHandler implements BotMessageHandler {

    private final BotConfig config;

    public RichReplyDemoHandler(BotConfig config) {
        this.config = config;
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        String msg = message.trim();
        return msg.equals("测试") || msg.equalsIgnoreCase("/demo");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        RichReplyDemo.run(event, config);
    }

    @Override
    public String name() {
        return "rich-reply-demo";
    }

    @Override
    public int priority() {
        return 90;
    }
}
