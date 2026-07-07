package org.windy.xingtubot.module;

import org.windy.xingtubot.common.command.impl.AnimePicCommand;
import org.windy.xingtubot.common.command.impl.FortuneCommand;
import org.windy.xingtubot.common.command.impl.TextImageCommand;
import org.windy.xingtubot.common.command.impl.WeatherCommand;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.handler.impl.RichReplyDemoHandler;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;

/**
 * 群娱乐模块：天气、运势、随机动漫图、文字生图、富消息 demo。
 */
public final class FunModule implements BotModule {

    @Override
    public String name() {
        return "fun";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        BotConfig config = ctx.config();

        regIfCmd(ctx, "weather-enable", true, WeatherCommand::new);
        regIfCmd(ctx, "fortune-enable", true, FortuneCommand::new);
        regIfCmd(ctx, "anime-enable", true, AnimePicCommand::new);

        // 文字生图依赖 TextImageRenderer（从主插件服务总线获取）
        TextImageRenderer textImage = ctx.getService(TextImageRenderer.class);
        if (textImage != null) {
            regIfCmd(ctx, "textimage-enable", true, () -> new TextImageCommand(textImage));
        }

        regIf(ctx, "demo-enable", false, () -> new RichReplyDemoHandler(config));

        ctx.logger().info("[Fun] 群娱乐模块已加载");
    }

    private void regIf(ModuleContext ctx, String key, boolean def,
                       java.util.function.Supplier<org.windy.xingtubot.common.handler.MessageHandler> supplier) {
        if (ctx.config().getBoolean(key, def)) {
            ctx.registry().register(supplier.get());
        }
    }

    private void regIfCmd(ModuleContext ctx, String key, boolean def,
                          java.util.function.Supplier<org.windy.xingtubot.common.command.GroupCommand> supplier) {
        if (ctx.config().getBoolean(key, def)) {
            ctx.registry().register(supplier.get());
        }
    }
}
