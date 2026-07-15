package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.command.GroupCommand;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.handler.MessageHandler;

/**
 * Public capability for registering bot message handlers and group commands.
 */
public interface CommandRegistrar {

    /**
     * @deprecated Use {@link #registerMessageHandler(BotMessageHandler)}.
     */
    @Deprecated
    void registerHandler(MessageHandler handler);

    default void registerMessageHandler(BotMessageHandler handler) {
        throw new UnsupportedOperationException("registerMessageHandler is not supported by this implementation");
    }

    /**
     * @deprecated Use {@link #registerCommand(BotCommand)}.
     */
    @Deprecated
    void registerCommand(GroupCommand command);

    default void registerCommand(BotCommand command) {
        throw new UnsupportedOperationException("registerCommand(BotCommand) is not supported by this implementation");
    }
}
