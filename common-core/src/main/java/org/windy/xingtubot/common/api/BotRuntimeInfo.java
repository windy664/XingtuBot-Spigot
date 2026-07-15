package org.windy.xingtubot.common.api;

/**
 * Public read-only runtime information about the running bot instance.
 */
public interface BotRuntimeInfo {

    String getBotName();

    String getBotAppId();

    boolean isDebugEnabled();
}
