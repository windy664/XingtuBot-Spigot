package org.windy.xingtubot.ext.xtfun;

import net.md_5.bungee.api.plugin.Plugin;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.FunModule;

public class FunBungeeCordPlugin extends Plugin {
    private BotModule module;
    @Override public void onEnable() {
        XingtuBotHost host = findHost();
        if (host == null) { getLogger().severe("[Fun] 找不到主插件"); return; }
        BotLogger logger = new BotLogger() { @Override public void info(String m) { getLogger().info(m); } @Override public void warn(String m) { getLogger().warning(m); } };
        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());
        module = ExtensionBootstrap.enable(host, new FunModule(), config, logger, getDataFolder());
    }
    @Override public void onDisable() { ExtensionBootstrap.disable(module); }
    private XingtuBotHost findHost() { net.md_5.bungee.api.plugin.Plugin p = getProxy().getPluginManager().getPlugin("XingtuBotBungeeCord"); return p instanceof XingtuBotHostProvider ? ((XingtuBotHostProvider) p).getHost() : null; }
}
