package org.windy.xingtubot.common.handler;

import org.windy.xingtubot.common.event.BotMessageContext;

/**
 * Stable message handler API for extensions.
 */
public interface BotMessageHandler {

    boolean matches(String message, BotMessageContext event);

    void handle(String message, BotMessageContext event);

    String name();

    default int priority() {
        return 100;
    }

    default boolean adminOnly() {
        return false;
    }

    default boolean acceptsWithoutMention() {
        return false;
    }

    default boolean adminFor(String message) {
        return adminOnly();
    }

    default java.util.List<String> triggers() {
        return java.util.Collections.emptyList();
    }

    default java.util.List<MenuEntry> menuEntries() {
        return java.util.Collections.emptyList();
    }

    default String usage() {
        return null;
    }

    default String description() {
        return "";
    }

    default String category() {
        return "";
    }

    default void init(HandlerContext ctx) {
    }

    default void shutdown() {
    }
}
