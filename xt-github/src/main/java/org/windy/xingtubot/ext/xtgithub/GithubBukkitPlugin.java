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
        XingtuBotHost host = getServer().getServicesManager().load(XingtuBotHost.class);

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
