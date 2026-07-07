package org.windy.xingtubot.ext.xtchatlink;

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
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.ChatlinkModule;
import org.windy.xingtubot.velocity.GroupChatLink;

import java.nio.file.Path;
import java.util.List;

/**
 * 群服互联扩展 · Velocity 主类。
 *
 * <p>创建 {@link GroupChatLink}（游戏↔QQ 双向聊天桥）并注册为服务，
 * 主插件在 bot 连接后注入 apiClient。
 */
@Plugin(
        id = "xingtubot-chatlink",
        name = "XingtuBot-Chatlink",
        version = "2.2.1",
        authors = {"风吟"},
        dependencies = {
                @Dependency(id = "xingtubotvelocity")
        }
)
public class ChatlinkVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private BotModule module;
    private GroupChatLink groupChatLink;

    @Inject
    public ChatlinkVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
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

        // QQ→游戏：注册 GroupChatHandler（兜底广播）；并注入机器人消息回显到游戏的平台广播器
        ChatlinkModule chatlink = new ChatlinkModule(proxy, null).withGameBroadcaster(line -> {
            net.kyori.adventure.text.Component comp =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().deserialize(line);
            for (com.velocitypowered.api.proxy.Player p : proxy.getAllPlayers()) p.sendMessage(comp);
        });
        module = ExtensionBootstrap.enable(host, chatlink, config, botLogger, dataDir.toFile());

        // 游戏→QQ：创建 GroupChatLink 并注册为服务
        if (config.getBoolean("chatreply-enable", true) && host != null) {
            groupChatLink = new GroupChatLink(proxy, this,
                    config.getString("chat-format", "§b[QQ群] §f"),
                    config.getString("group-prefix", "[游戏]"),
                    config.getBoolean("chat-reply-button", false));
            // 注入调试日志器：DebugFlag 开启时在群服互联两条链路入口/兜底打点
            groupChatLink.setLogger(botLogger);
            // game→QQ 聊天行 markdown 模板（{player}/{message}）
            groupChatLink.setGameFormat(config.getString("chatlink-format",
                    org.windy.xingtubot.common.util.ChatlinkFormat.DEFAULT));
            // 主动消息能力：传「供给器」每次发送时现取核心的 ProactiveSender service。
            // 不能在此处一次性 getService 缓存——本扩展 onEnable 时核心可能还没注册 → 缓存 null 且永不自愈。
            groupChatLink.setSender(
                    () -> host.getService(org.windy.xingtubot.common.module.capability.ProactiveSender.class));

            // 配置群服互联白名单
            List<String> allowedGroups = config.getStringList("allowed-groups");
            groupChatLink.setAllowedGroups(allowedGroups);

            // 配置敏感词过滤器
            if (config.getBoolean("Enable", true)) {
                org.windy.xingtubot.common.service.SensitiveFilter filter =
                        org.windy.xingtubot.common.service.SensitiveFilter.fromConfig(config, botLogger);
                filter.reloadCloudWords();
                groupChatLink.setSensitiveFilter(filter);
            }

            // 注册为服务（主插件 bot-ready 时注入 apiClient）
            host.registerService(GroupChatLink.class, groupChatLink);

            // 警告：未配具体群
            warnNoConcreteGroup(config, botLogger);

            botLogger.info("[Chatlink] 游戏→QQ 转发已启用（Velocity）");
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ExtensionBootstrap.disable(module);
    }

    private void warnNoConcreteGroup(BotConfig config, BotLogger logger) {
        List<String> groups = config.getStringList("allowed-groups");
        boolean hasConcrete = false;
        for (String g : groups) {
            if (g != null && !"*".equals(g) && !g.trim().isEmpty()) { hasConcrete = true; break; }
        }
        if (!hasConcrete) {
            logger.warn("[Chatlink] allowed-groups 未配置具体群（为空或 \"*\"），游戏聊天将无法主动转发到 QQ 群。");
            logger.warn("   请在 xt-chatlink/config.yml 把 allowed-groups 配成具体群 openid。");
        }
    }
}
