package org.windy.xingtubot.ext.xtgroup;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.GroupFeaturesModule;

import java.nio.file.Path;

/**
 * 群功能扩展 · Velocity 主类（迎送 + 自定义回复 + 自定义指令）。
 */
@Plugin(
        id = "xingtubot-group-features",
        name = "XingtuBot-Group",
        version = "2.2.1",
        authors = {"风吟"},
        dependencies = {
                @Dependency(id = "xingtubotvelocity")
        }
)
public class GroupVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private BotModule module;

    @Inject
    public GroupVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        XingtuBotHost host = proxy.getPluginManager().getPlugin("xingtubotvelocity")
                .flatMap(PluginContainer::getInstance)
                .filter(p -> p instanceof XingtuBotHostProvider)
                .map(p -> ((XingtuBotHostProvider) p).getHost())
                .orElse(null);

        BotLogger botLogger = new BotLogger() {
            @Override public void info(String msg) { logger.info(msg); }
            @Override public void warn(String msg) { logger.warn(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(dataDir.toFile(), getClass().getClassLoader());

        module = ExtensionBootstrap.enable(host, new GroupFeaturesModule(), config, botLogger, dataDir.toFile());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ExtensionBootstrap.disable(module);
    }
}
