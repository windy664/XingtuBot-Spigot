package org.windy.xingtubot.velocity;

import org.slf4j.Logger;
import org.windy.xingtubot.common.platform.BotLogger;

/**
 * 用 Velocity 的 SLF4J Logger 实现 common 的 BotLogger。
 */
public class VelocityBotLogger implements BotLogger {
    private final Logger logger;

    public VelocityBotLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String msg) {
        logger.info(msg);
    }

    @Override
    public void warn(String msg) {
        logger.warn(msg);
    }
}
