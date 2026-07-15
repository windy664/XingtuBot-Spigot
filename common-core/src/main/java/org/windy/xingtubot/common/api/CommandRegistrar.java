package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.handler.BotMessageHandler;

/**
 * Public capability for registering bot message handlers and group commands.
 */
public interface CommandRegistrar {

    default void registerMessageHandler(BotMessageHandler handler) {
        throw new UnsupportedOperationException("registerMessageHandler is not supported by this implementation");
    }

    default void registerCommand(BotCommand command) {
        throw new UnsupportedOperationException("registerCommand(BotCommand) is not supported by this implementation");
    }
}
