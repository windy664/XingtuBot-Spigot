package org.windy.xingtubot.ext.xtfun.feature;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.ext.xtfun.command.FortuneCommand;

public class FortuneFeature implements FunFeature {
    @Override public String configKey() { return "fortune-enable"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public BotCommand createCommand(ModuleContext ctx) { return new FortuneCommand(); }
}
