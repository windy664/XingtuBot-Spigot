package org.windy.xingtubot.module;

import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.module.ai.AiChatHandler;

/**
 * AI 对话模块：LLM 聊天 observer + 注册 AiService 供其他附属（如模组查询 LLM 别名）软依赖。
 */
public final class AiModule implements BotModule {

    @Override
    public String name() {
        return "ai";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        BotConfig config = ctx.config();

        if (!config.getBoolean("llm-enable", false)) {
            ctx.logger().info("[AI] AI 对话已禁用（llm-enable: false）。");
            return;
        }

        String apiKey = config.getString("llm-api-key", "");
        if (apiKey.isEmpty()) {
            ctx.logger().warn("[AI] llm-enable 已开但未配置 llm-api-key，AI 对话未启用");
            return;
        }

        String baseUrl = config.getString("llm-base-url", "https://api.deepseek.com");
        String model = config.getString("llm-model", "deepseek-chat");

        AiService aiService = new AiService(apiKey, baseUrl, model);
        // 注册到服务总线，供 xt-modquery 等附属 getService(AiService.class) 获取
        ctx.registerService(AiService.class, aiService);

        // 注册 AI 聊天 observer（与命令/群服互联并行响应）
        AiChatHandler handler = new AiChatHandler(
                aiService, config, ctx.logger(),
                () -> ctx.registry().getManagedPrefixes());
        ctx.registry().registerObserver(handler);

        ctx.logger().info("[AI] AI 对话已加载（模型: " + model + "）");
    }
}
