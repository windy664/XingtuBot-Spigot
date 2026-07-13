package org.windy.xingtubot.module;

import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.module.ai.AiChatHandler;
import org.windy.xingtubot.module.ai.StickerManager;

/**
 * AI 对话模块：LLM 聊天 observer + 注册 AiService 供其他附属软依赖。
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

        String baseUrl = config.getString("llm-base-url", "https://token-plan-cn.xiaomimimo.com/v1");
        String model = config.getString("llm-model", "mimo-v2.5");

        AiService aiService = new AiService(apiKey, baseUrl, model);
        ctx.registerService(AiService.class, aiService);

        // 加载表情包
        StickerManager stickerManager = new StickerManager(ctx.dataFolder(), ctx.logger());
        stickerManager.load();

        // 注册 AI 聊天 observer
        AiChatHandler handler = new AiChatHandler(
                aiService, config, ctx.logger(),
                () -> ctx.registry().getManagedPrefixes(),
                ctx.permission(),
                stickerManager,
                ctx.dataFolder());
        ctx.registry().registerObserver(handler);

        String omniModel = config.getString("llm-model-omni", "mimo-v2-omni");
        ctx.logger().info("[AI] AI 对话已加载（模型: " + model + ", 多模态: " + omniModel + "）");
    }
}
