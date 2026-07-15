package org.windy.xingtubot.common.command;

import org.windy.xingtubot.common.event.BotMessageContext;

/**
 * Stable command API for extensions.
 */
public interface BotCommand {

    boolean matches(String message);

    void handle(String message, BotMessageContext event);

    String name();

    default boolean adminOnly() {
        return false;
    }

    default boolean adminFor(String message) {
        return adminOnly();
    }

    default java.util.List<String> triggers() {
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
}
