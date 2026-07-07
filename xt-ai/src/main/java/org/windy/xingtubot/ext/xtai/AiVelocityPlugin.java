package org.windy.xingtubot.ext.xtai;

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
import org.windy.xingtubot.module.AiModule;

import java.nio.file.Path;

/**
 * AI 对话扩展 · Velocity 主类。
 */
@Plugin(
        id = "xingtubot-ai",
        name = "XingtuBot-AI",
        version = "2.2.1",
        authors = {"风吟"},
        dependencies = {
                @Dependency(id = "xingtubotvelocity")
        }
)
public class AiVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private BotModule module;

    @Inject
    public AiVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
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

        module = ExtensionBootstrap.enable(host, new AiModule(), config, botLogger, dataDir.toFile());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ExtensionBootstrap.disable(module);
    }
}
