package org.windy.xingtubot.springboot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.windy.xingtubot.common.platform.BotLogger;

/**
 * Spring Boot 日志适配：桥接 common-core 的 {@link BotLogger} 到 SLF4J。
 */
public class SpringBootBotLogger implements BotLogger {

    private final Logger delegate;

    public SpringBootBotLogger(Class<?> clazz) {
        this.delegate = LoggerFactory.getLogger(clazz);
    }

    public SpringBootBotLogger(String name) {
        this.delegate = LoggerFactory.getLogger(name);
    }

    @Override
    public void info(String msg) {
        delegate.info(msg);
    }

    @Override
    public void warn(String msg) {
        delegate.warn(msg);
    }
}
