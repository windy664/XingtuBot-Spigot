package org.windy.xingtubot.ext.xtfun.feature;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.ext.xtfun.command.AnimePicCommand;

import java.util.List;

public class AnimePicFeature implements FunFeature {
    @Override public String configKey() { return "anime-enable"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public BotCommand createCommand(ModuleContext ctx) {
        List<String> sources = ctx.config().getStringList("anime-sources");
        return new AnimePicCommand(sources);
    }
}
