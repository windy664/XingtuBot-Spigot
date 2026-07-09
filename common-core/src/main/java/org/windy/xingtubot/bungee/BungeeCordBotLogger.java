package org.windy.xingtubot.bungee;

import org.windy.xingtubot.common.platform.BotLogger;

import java.util.logging.Logger;

/**
 * BungeeCord 日志适配。
 */
public class BungeeCordBotLogger implements BotLogger {
    private final Logger logger;

    public BungeeCordBotLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String msg) {
        logger.info(msg);
    }

    @Override
    public void warn(String msg) {
        logger.warning(msg);
    }
}
