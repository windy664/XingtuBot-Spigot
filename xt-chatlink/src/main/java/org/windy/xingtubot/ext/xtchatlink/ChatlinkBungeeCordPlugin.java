package org.windy.xingtubot.ext.xtchatlink;

import net.md_5.bungee.api.plugin.Plugin;
import org.windy.xingtubot.bungee.BungeeCordGroupChatLink;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.ChatlinkModule;

import java.util.List;

/**
 * 群服互联扩展 · BungeeCord 主类。
 * 创建 BungeeCordGroupChatLink（游戏↔QQ 双向聊天桥）并注册为服务。
 */
public class ChatlinkBungeeCordPlugin extends Plugin {

    private BotModule module;
    private BungeeCordGroupChatLink groupChatLink;

    @Override
    public void onEnable() {
        XingtuBotHost host = findHost();
        if (host == null) { getLogger().severe("[Chatlink] 找不到主插件 XingtuBotBungeeCord"); return; }

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        // QQ→游戏；并注入机器人消息回显到游戏的平台广播器
        ChatlinkModule chatlink = new ChatlinkModule(this, null).withGameBroadcaster(line -> {
            net.md_5.bungee.api.chat.TextComponent comp = new net.md_5.bungee.api.chat.TextComponent(line);
            for (net.md_5.bungee.api.connection.ProxiedPlayer p : getProxy().getPlayers()) p.sendMessage(comp);
        });
        module = ExtensionBootstrap.enable(host, chatlink, config, logger, getDataFolder());

        // 游戏→QQ
        if (config.getBoolean("chatreply-enable", true)) {
            groupChatLink = new BungeeCordGroupChatLink(getProxy(), this,
                    config.getString("chat-format", "§b[QQ群] §f"));

            List<String> allowedGroups = org.windy.xingtubot.common.util.GroupTargets
                    .resolveConcrete(host, config.getStringList("allowed-groups"));
            groupChatLink.setAllowedGroups(allowedGroups);

            // 敏感词过滤器：从服务总线获取（core 注册的全局单例）
            org.windy.xingtubot.common.service.SensitiveFilter sf =
                    host.getService(org.windy.xingtubot.common.service.SensitiveFilter.class);
            if (sf != null) {
                groupChatLink.setSensitiveFilter(sf);
            }

            // 调试日志器：核心调试开关开启时在群服互联两条链路入口/兜底打点
            groupChatLink.setLogger(logger);
            // game→QQ 聊天行 markdown 模板（{player}/{message}）
            groupChatLink.setGameFormat(config.getString("chatlink-format",
                    org.windy.xingtubot.chatlink.util.ChatlinkFormat.DEFAULT));
            // 主动消息能力：传「供给器」每次发送时现取核心的 ProactiveSender service。
            // 不能在此处一次性 getService 缓存——本扩展 onEnable 时核心可能还没注册 → 缓存 null 且永不自愈。
            groupChatLink.setSender(
                    () -> host.getService(org.windy.xingtubot.common.module.capability.ProactiveSender.class));

            host.registerService(BungeeCordGroupChatLink.class, groupChatLink);
            warnNoConcreteGroup(allowedGroups, logger);
            logger.info("[Chatlink] 游戏→QQ 转发已启用（BungeeCord）");
        }
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
    }

    private XingtuBotHost findHost() {
        net.md_5.bungee.api.plugin.Plugin main = getProxy().getPluginManager().getPlugin("XingtuBotBungeeCord");
        if (main instanceof XingtuBotHostProvider) return ((XingtuBotHostProvider) main).getHost();
        return null;
    }

    private void warnNoConcreteGroup(List<String> groups, BotLogger logger) {
        if (groups == null || groups.isEmpty()) {
            logger.warn("[Chatlink] allowed-groups 未配置具体群，游戏聊天将无法主动转发到 QQ 群。");
        }
    }
}
