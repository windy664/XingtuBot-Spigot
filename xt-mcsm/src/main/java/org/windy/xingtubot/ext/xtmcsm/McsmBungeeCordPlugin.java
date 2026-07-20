package org.windy.xingtubot.ext.xtmcsm;

import net.md_5.bungee.api.plugin.Plugin;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.mcsm.McsmModule;

/**
 * MCSM 面板管理扩展 · BungeeCord 主类。
 */
public final class McsmBungeeCordPlugin extends Plugin {

    private BotModule module;

    @Override
    public void onEnable() {
        XingtuBotHost host = null;
        try {
            net.md_5.bungee.api.plugin.Plugin corePlugin =
                    getProxy().getPluginManager().getPlugin("XingtuBotBungeeCord");
            if (corePlugin instanceof XingtuBotHostProvider) {
                host = ((XingtuBotHostProvider) corePlugin).getHost();
            }
        } catch (Exception ignored) {
        }

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        module = ExtensionBootstrap.enable(host, new McsmModule(), config, logger, getDataFolder());
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
    }
}
