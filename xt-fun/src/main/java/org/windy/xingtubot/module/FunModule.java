package org.windy.xingtubot.module;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.ext.xtfun.feature.*;

/**
 * 群娱乐模块：自动扫描注册所有 {@link FunFeature}。
 *
 * <p>新增功能只需：
 * <ol>
 *   <li>写一个 {@code XxxFeature implements FunFeature}</li>
 *   <li>在下面 {@code FEATURES} 列表加一行</li>
 * </ol>
 */
public final class FunModule implements BotModule {

    /** 所有娱乐功能。新增功能在这里加一行即可。 */
    private static final FunFeature[] FEATURES = {
            new WeatherFeature(),
            new FortuneFeature(),
            new AnimePicFeature(),
            new TextImageFeature(),
            new RichReplyDemoFeature(),
    };

    @Override
    public String name() {
        return "fun";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        int count = 0;
        for (FunFeature feature : FEATURES) {
            if (!ctx.config().getBoolean(feature.configKey(), feature.defaultEnabled())) {
                continue;
            }
            BotCommand cmd = feature.createCommand(ctx);
            if (cmd != null) {
                ctx.registry().register(cmd);
                count++;
            }
            BotMessageHandler handler = feature.createHandler(ctx);
            if (handler != null) {
                ctx.registry().register(handler);
                count++;
            }
        }
        ctx.logger().info("[Fun] 群娱乐模块已加载 (" + count + " 个功能)");
    }
}
