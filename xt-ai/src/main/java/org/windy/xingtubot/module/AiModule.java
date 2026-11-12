package org.windy.xingtubot.module;

import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.module.ai.AiChatHandler;
import org.windy.xingtubot.module.ai.AiExecutor;

import java.io.IOException;
import java.util.List;

/**
 * AI 对话模块：LLM 聊天 observer + 注册 AiService 供其他附属软依赖。
 */
public final class AiModule implements BotModule {

    private AiChatHandler handler;

    @Override
    public String name() {
        return "ai";
    }

    @Override
    public String displayName() {
        return "🤖 AI 对话";
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

        AiService aiService = new AiService(apiKey, baseUrl, model,
                readDouble(config, "llm-temperature", 0.9),
                config.getInt("llm-max-tokens", 0),
                config.getInt("llm-connect-timeout-ms", 5000),
                config.getInt("llm-read-timeout-ms", 25000),
                config.getInt("llm-retry", 1));
        ctx.registerService(AiService.class, aiService);

        AiService chimeInJudgeService = createChimeInJudgeService(config, apiKey, baseUrl, model, ctx);

        // AI 专属线程池：LLM 调用绝不在 QQ 网关线程上发生
        AiExecutor executor = new AiExecutor(ctx.logger());

        // 注册 AI 聊天 observer（知识库走服务总线软依赖：xt-group 未装/晚装都安全）
        handler = new AiChatHandler(
                aiService, chimeInJudgeService, config, ctx.logger(),
                ctx.registry()::isHandledByCommand,
                ctx.permission(),
                ctx.dataFolder(),
                () -> knowledgeLines(ctx),
                executor);
        ctx.registry().registerObserver(handler);

        String omniModel = config.getString("llm-model-omni", "mimo-v2-omni");
        ctx.logger().info("[AI] AI 对话已加载（主模型: " + model + " @ " + baseUrl
                + ", 多模态: " + omniModel
                + ", 插话判定: " + describeJudge(config, chimeInJudgeService, model, baseUrl)
                + ", 每小时额度: " + config.getInt("ai-max-calls-per-hour", 60) + " 次请求）");
    }

    @Override
    public void onDisable() {
        if (handler != null) {
            handler.shutdown();
            handler = null;
        }
    }

    /**
     * 创建「要不要插话」的判定服务。
     *
     * <h3>为什么不能隐式跨 provider 回退</h3>
     * 旧版逻辑：key 为空就回退用主模型的 key，但 base-url 仍读自己的配置项。
     * 默认配置下这会变成「拿小米的 key 去打 OpenRouter」——每次 401，
     * 错误又被 catch 成"不插话"，<b>只在日志留一行 warn</b>，
     * 表现就是"机器人只会应 @，从不主动聊天"，极难排查。
     *
     * <p>现在的规则：
     * <ul>
     *   <li>base-url 和 key 都留空 → 完全跟随主模型（同 provider、同 key、同模型）；</li>
     *   <li>只填了 base-url 没填 key → <b>禁用</b>并显式报错，绝不拿别家 key 去凑；</li>
     *   <li>只填了 key 没填 base-url → 视为「同 provider 的另一个模型」，base-url 跟随主模型；</li>
     *   <li>两个都填 → 用这对配置，并做一次连通性自检。</li>
     * </ul>
     */
    private AiService createChimeInJudgeService(BotConfig config, String mainApiKey,
                                                 String mainBaseUrl, String mainModel,
                                                 ModuleContext ctx) {
        if (!config.getBoolean("chime-in-judge-enable", true)) {
            ctx.logger().info("[AI] 自主插话判定已关闭（chime-in-judge-enable: false）");
            return null;
        }

        String judgeBase = config.getString("chime-in-judge-base-url", "").trim();
        String judgeKey = config.getString("chime-in-judge-api-key", "").trim();
        String judgeModel = config.getString("chime-in-judge-model", "").trim();

        boolean explicitBase = !judgeBase.isEmpty();
        boolean explicitKey = !judgeKey.isEmpty();

        if (explicitBase && !explicitKey) {
            ctx.logger().warn("[AI] 已配置 chime-in-judge-base-url (" + judgeBase
                    + ") 但未配置 chime-in-judge-api-key —— 自主插话判定已禁用。"
                    + "请补上该 provider 的 key，或把 chime-in-judge-base-url 也留空以跟随主模型。");
            return null;
        }

        if (judgeBase.isEmpty()) judgeBase = mainBaseUrl;     // 留空 = 跟随主模型
        if (judgeKey.isEmpty()) judgeKey = mainApiKey;       // 留空 = 跟随主模型
        if (judgeModel.isEmpty()) judgeModel = mainModel;    // 留空 = 跟随主模型

        AiService judge = new AiService(judgeKey, judgeBase, judgeModel,
                readDouble(config, "chime-in-judge-temperature", 0.0),
                config.getInt("chime-in-judge-max-tokens", 64),
                config.getInt("llm-connect-timeout-ms", 5000),
                config.getInt("llm-read-timeout-ms", 25000),
                0);   // 判定失败不重试，快速失败更省

        // 启动自检：宁可早点说清楚，也不要运行中静默地 401。
        // 放后台线程跑，避免网络慢时把插件启动卡住。
        final String checkModel = judgeModel;
        final String checkBase = judgeBase;
        Thread check = new Thread(() -> {
            String err = selfCheck(judge);
            if (err != null) {
                ctx.logger().warn("[AI] 插话判定自检未通过（" + err
                        + "）—— 自主插话将不可用，但 @ 直接回复不受影响。");
            } else {
                ctx.logger().info("[AI] 插话判定自检通过（" + checkModel + " @ " + checkBase + "）");
            }
        }, "ai-judge-selfcheck");
        check.setDaemon(true);
        check.start();
        return judge;
    }

    /** 发一个最小请求验证连通性与鉴权；返回 null 表示通过，否则返回错误描述。 */
    private String selfCheck(AiService judge) {
        try {
            judge.chat("ping");
            return null;
        } catch (IOException e) {
            return e.getMessage();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private String describeJudge(BotConfig config, AiService judge, String mainModel, String mainBaseUrl) {
        if (judge == null) return "未启用";
        String model = config.getString("chime-in-judge-model", "").trim();
        if (model.isEmpty()) model = mainModel;
        String base = config.getString("chime-in-judge-base-url", "").trim();
        if (base.isEmpty()) base = mainBaseUrl;
        return model + " @ " + base;
    }

    private static double readDouble(BotConfig config, String path, double def) {
        String raw = config.getString(path, "");
        if (raw == null || raw.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 附属间软依赖：xt-group 把问答知识以 JDK {@code Supplier<List<String>>} 注册在
     * 服务总线 key "xingtubot.knowledge" 下（JDK 类型跨 classloader 可见，无需共同接口/反射）。
     * xt-group 未装时总线查不到，返回 null。
     */
    @SuppressWarnings("unchecked")
    private static List<String> knowledgeLines(ModuleContext ctx) {
        Object service = ctx.getServiceObject("xingtubot.knowledge");
        if (!(service instanceof java.util.function.Supplier)) return null;
        try {
            return ((java.util.function.Supplier<List<String>>) service).get();
        } catch (Exception e) {
            return null;
        }
    }
}
