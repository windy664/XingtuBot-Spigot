package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.event.BotMessageEvent;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * Public capability for observing or intercepting command execution.
 */
public interface CommandHookBus {

    void beforeCommand(BiPredicate<String, ? super BotMessageEvent> hook);

    void afterCommand(BiConsumer<String, ? super BotMessageEvent> hook);

    default void beforeCommandReadOnly(CommandBeforeHook hook) {
        if (hook != null) {
            beforeCommand((command, event) -> hook.test(command, event));
        }
    }

    default void afterCommandReadOnly(CommandAfterHook hook) {
        if (hook != null) {
            afterCommand((command, event) -> hook.accept(command, event));
        }
    }
}
