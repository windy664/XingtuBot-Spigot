package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.event.BotMessage;

/**
 * Read-only command hook executed before a command handler.
 */
@FunctionalInterface
public interface CommandBeforeHook {

    /**
     * @return true to allow the command, false to block it
     */
    boolean test(String command, BotMessage message);
}
