package org.windy.xingtubot.ext.xtfun.feature;

import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.ext.xtfun.handler.RichReplyDemoHandler;

public class RichReplyDemoFeature implements FunFeature {
    @Override public String configKey() { return "demo-enable"; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public BotMessageHandler createHandler(ModuleContext ctx) {
        return new RichReplyDemoHandler(ctx.config());
    }
}
