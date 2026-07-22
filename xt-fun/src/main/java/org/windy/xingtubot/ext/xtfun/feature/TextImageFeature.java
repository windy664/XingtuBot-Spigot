package org.windy.xingtubot.ext.xtfun.feature;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.ext.xtfun.command.TextImageCommand;

public class TextImageFeature implements FunFeature {
    @Override public String configKey() { return "textimage-enable"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public BotCommand createCommand(ModuleContext ctx) {
        TextImageRenderer renderer = ctx.getService(TextImageRenderer.class);
        return renderer != null ? new TextImageCommand(renderer) : null;
    }
}
