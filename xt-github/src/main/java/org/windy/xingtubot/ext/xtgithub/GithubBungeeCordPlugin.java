package org.windy.xingtubot.ext.xtgithub;

import net.md_5.bungee.api.plugin.Plugin;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.GithubModule;

/**
 * GitHub 项目追踪扩展 · BungeeCord 主类。
 */
public class GithubBungeeCordPlugin extends Plugin {

    private BotModule module;

    @Override
    public void onEnable() {
        XingtuBotHost host = findHost();
        if (host == null) {
            getLogger().severe("[Github] 找不到主插件 (XingtuBotBungeeCord)，扩展加载失败！");
            return;
        }

        BotLogger logger = new BotLogger() {
            @Override public void info(String m) { getLogger().info(m); }
            @Override public void warn(String m) { getLogger().warning(m); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        module = ExtensionBootstrap.enable(host, new GithubModule(), config, logger, getDataFolder());
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
    }

    private XingtuBotHost findHost() {
        // 尝试获取 BungeeCord 平台的主机器人核心
        net.md_5.bungee.api.plugin.Plugin p = getProxy().getPluginManager().getPlugin("XingtuBotBungeeCord");
        return p instanceof XingtuBotHostProvider ? ((XingtuBotHostProvider) p).getHost() : null;
    }
}