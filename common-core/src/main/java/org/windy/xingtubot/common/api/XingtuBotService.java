package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.BotMessageHandler;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * Platform-neutral public API exposed by XingtuBot.
 */
public interface XingtuBotService extends CommandRegistrar, MessageSender, CommandHookBus, BotRuntimeInfo {

    int API_VERSION = 4;

    default int apiVersion() {
        return API_VERSION;
    }

    void registerMessageHandler(BotMessageHandler handler);

    void registerCommand(BotCommand command);

    void sendToGroupMarkdown(String groupOpenId, String markdownContent, String keyboardTemplateId);

    void beforeCommand(BiPredicate<String, ? super BotMessageEvent> hook);

    void afterCommand(BiConsumer<String, ? super BotMessageEvent> hook);

    String getBotAppId();
}
