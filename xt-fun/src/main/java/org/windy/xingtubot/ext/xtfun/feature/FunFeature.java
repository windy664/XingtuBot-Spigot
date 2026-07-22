package org.windy.xingtubot.ext.xtfun.feature;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.module.ModuleContext;

/**
 * 娱乐功能 SPI：每个功能自包含配置键、默认开关、命令/处理器工厂。
 *
 * <p>新增功能只需：
 * <ol>
 *   <li>写一个类实现本接口</li>
 *   <li>在 {@code FunModule.FEATURES} 列表里加一行</li>
 * </ol>
 * 不需要改 FunModule 的注册逻辑。
 */
public interface FunFeature {

    /** 配置键（如 "weather-enable"）。 */
    String configKey();

    /** 默认是否启用。 */
    boolean defaultEnabled();

    /** 创建命令实例。返回 null 表示本功能不是命令型。 */
    default BotCommand createCommand(ModuleContext ctx) {
        return null;
    }

    /** 创建处理器实例。返回 null 表示本功能不是处理器型。 */
    default BotMessageHandler createHandler(ModuleContext ctx) {
        return null;
    }
}
