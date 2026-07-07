package org.windy.xingtubot.ext.xtgithub;

import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.GithubModule;

/**
 * GitHub 项目追踪扩展 · Bukkit 主类。
 */
public final class GithubBukkitPlugin extends JavaPlugin {

    private BotModule module;

    @Override
    public void onEnable() {
        // 从 Bukkit 注册表服务获取主核心
        XingtuBotHost host = getServer().getServicesManager().load(XingtuBotHost.class);

        if (host == null) {
            getLogger().warning("[Github] 找不到主插件服务，扩展加载失败！请确认前置是否已安装。");
            return;
        }

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        module = ExtensionBootstrap.enable(host, new GithubModule(), config, logger, getDataFolder());
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
    }
}