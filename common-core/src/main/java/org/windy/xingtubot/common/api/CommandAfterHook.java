package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.event.BotMessage;

/**
 * Read-only command hook executed after a command handler.
 */
@FunctionalInterface
public interface CommandAfterHook {

    void accept(String command, BotMessage message);
}
