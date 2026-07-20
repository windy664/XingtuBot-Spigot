package org.windy.xingtubot.common.runtime;

import org.windy.xingtubot.common.api.BotRuntimeInfo;

/**
 * BotRuntimeInfo 的回退实现：XingtuBotServiceImpl 还未创建时使用。
 * 直接委托给 BotRuntimeState（同 classloader 内部访问）。
 */
enum BotRuntimeStateFallback implements BotRuntimeInfo {
    INSTANCE;

    @Override public String getBotName() { return BotRuntimeState.getBotName(); }
    @Override public String getBotAppId() { return ""; }
    @Override public boolean isDebugEnabled() { return BotRuntimeState.isDebugEnabled(); }
}
