package org.windy.xingtubot.bukkit;

import org.windy.xingtubot.common.platform.BotLogger;

import java.util.logging.Logger;

/**
 * 用插件 Logger 实现 common 的 BotLogger。
 */
public class SpigotBotLogger implements BotLogger {
    private final Logger logger;

    public SpigotBotLogger(Logger logger) {
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
