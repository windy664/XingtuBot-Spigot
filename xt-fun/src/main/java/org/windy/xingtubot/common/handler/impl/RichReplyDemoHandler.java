package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.demo.RichReplyDemo;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.MessageHandler;

/**
 * 富消息 demo（发「测试」或「/demo」触发）。
 * 包装 {@link RichReplyDemo}，priority=90。
 */
public class RichReplyDemoHandler implements MessageHandler {

    private final BotConfig config;

    public RichReplyDemoHandler(BotConfig config) {
        this.config = config;
    }

    @Override
    public boolean matches(String message, BotMessageEvent event) {
        String msg = message.trim();
        return msg.equals("测试") || msg.equalsIgnoreCase("/demo");
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
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
