package org.windy.xingtubot.module;

import org.windy.xingtubot.common.ai.AiService; // 软依赖 xt-ai 注册的服务
import org.windy.xingtubot.common.command.impl.McmodCommand;
import org.windy.xingtubot.common.command.impl.ModWatchCommand;
import org.windy.xingtubot.common.command.impl.ModrinthCommand;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.capability.GameEcho;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.service.BaiduTranslateService;
import org.windy.xingtubot.common.service.McmodApiService;
import org.windy.xingtubot.common.service.ModUpdateService;
import org.windy.xingtubot.common.service.ModrinthApiService;
import org.windy.xingtubot.common.service.Translator;

/**
 * 模组工具模块：Modrinth 搜索（/mod /pack）、MCMOD 直查、模组更新订阅（/modwatch）、百度翻译。
 *
 * <p>内嵌百度翻译，注册 {@link Translator} 供 xt-github 软依赖。
 * 因提供服务，{@link #loadPriority()} 返回 10。
 */
public final class ModqueryModule implements BotModule {

    private ModUpdateService modUpdate;

    @Override
    public String name() {
        return "modquery";
    }

    @Override
    public int loadPriority() {
        return 10;
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        BotConfig config = ctx.config();

        // ===== 百度翻译 =====
        BaiduTranslateService translator = new BaiduTranslateService(ctx.logger());
        translator.loadConfig(config);
        ctx.registerService(Translator.class, translator);
        ctx.registerService(BaiduTranslateService.class, translator);

        // ===== 共享 Modrinth API（供 Modrinth 命令 + mcmod 的 /mod 兜底用）=====
        // 解耦：只要 modsearch 或 mcmod 任一开启就创建——mcmod 需要它做 /mod 搜空兜底 + /mr 备用入口。
        // 否则「modsearch-enable:false + mcmod-enable:true」时兜底/mr 会静默失效，与注释承诺不符。
        boolean modsearchEnable = config.getBoolean("modsearch-enable", true);
        boolean mcmodEnable = config.getBoolean("mcmod-enable", false);
        ModrinthApiService modrinthApi = null;
        if (modsearchEnable || mcmodEnable) {
            modrinthApi = new ModrinthApiService(ctx.logger());
            modrinthApi.setTranslator(translator);
            modrinthApi.setCurseforgeApiKey(config.getString("curseforge-api-key", ""));
            modrinthApi.setAliases(config.getStringMap("mod-aliases"));
            // LLM 辅助：从 xt-ai 的服务总线获取（软依赖，未装 xt-ai 则跳过）
            AiService llm = ctx.getService(AiService.class);
            if (llm != null) {
                modrinthApi.setLlm(llm);
            }
        }

        // ===== MCMOD 直查（默认关，优先级最高）=====
        // mcmod.cn 已上图片验证码反爬：需在 config 填 mcmod-cookie（管理员从浏览器 F12 复制
        // "_uuid=...; MCMOD_SEED=..."），机器人带 cookie + 浏览器头即可跨 IP 直连爬取（已实测可行）。
        // 先注册 → 抢到 /mod、/pack（中文原生免翻译）；并独占 /item、/tutorial。
        // /mod 搜空时自动兜底到 Modrinth（优先级 mcmod > modrinth）。
        if (mcmodEnable) {
            McmodApiService mcmod = new McmodApiService(ctx.logger());
            mcmod.setCookie(config.getString("mcmod-cookie", ""));
            McmodCommand mcmodCmd = new McmodCommand(mcmod);
            // mcmod 详情卡恒走 markdown（无开关）——产品定位 markdown-only。
            if (modrinthApi != null) mcmodCmd.setModrinthFallback(modrinthApi); // 恒非 null（上面已解耦）
            ctx.registry().register(mcmodCmd);
        }

        // ===== Modrinth 命令：mcmod 关时吃 /mod、/pack（主搜）；mcmod 开时它排在 mcmod 之后，实际只服务 /mr 备用入口 =====
        if (modrinthApi != null) {
            ModrinthCommand modrinthCmd = new ModrinthCommand(modrinthApi);
            // markdown 开关只对 Modrinth 有效（mcmod 恒 markdown）。新键 modrinth-markdown，兼容读旧键 mcmod-markdown。
            modrinthCmd.setMarkdownEnabled(true);
            ctx.registry().register(modrinthCmd);
        }

        // ===== 模组更新订阅（/modwatch）=====
        if (config.getBoolean("modwatch-enable", true)) {
            modUpdate = new ModUpdateService(config, ctx.logger(), null, translator);
            modUpdate.setDataDir(ctx.dataFolder());
            ProactiveSender sender = ctx.getService(ProactiveSender.class);
            if (sender != null) modUpdate.setProactiveSender(sender);
            // GameEcho 由 xt-chatlink 注册：惰性解析（按回显时取），避免与本扩展加载顺序耦合
            modUpdate.setGameEcho(text -> {
                GameEcho e = ctx.getService(GameEcho.class);
                if (e != null) e.echo(text);
            });
            modUpdate.start();
            ctx.registry().register(new ModWatchCommand(modUpdate));
        }

        ctx.logger().info("[Modquery] 模组工具已加载（翻译: " + (translator.isEnabled() ? "百度翻译" : "未配置") + "）");
    }

    @Override
    public void onDisable() {
        if (modUpdate != null) {
            modUpdate.stop();
            modUpdate = null;
        }
    }
}
