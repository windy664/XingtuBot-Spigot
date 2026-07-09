package org.windy.xingtubot.ext.xtchatlink;

import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.module.chatreply.ChatreplyModule;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.lock.PlayerLockManager;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.ChatlinkModule;

/**
 * 群服互联扩展 · Bukkit 主类。
 *
 * <p>QQ→游戏 由 ChatlinkModule 经 GameChatBridge 能力注册 GroupChatHandler；
 * 游戏→QQ 由本类创建 ChatreplyModule（GameChatForwarder 监听 + /messagereply 命令）。
 */
public final class ChatlinkBukkitPlugin extends JavaPlugin {

    private BotModule module;
    private ChatreplyModule chatreplyModule;

    @Override
    public void onEnable() {
        XingtuBotHost host = getServer().getServicesManager().load(XingtuBotHost.class);

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        // QQ→游戏：注册 GameChatBridge（用 xt-chatlink 自己的 GuildMessageListener，带 group openid 拼回复按钮）。
        // 必须在 ExtensionBootstrap.enable 之前注册——ChatlinkModule.onEnable 会 getService(GameChatBridge) 拿它。
        if (host != null) {
            host.registerService(org.windy.xingtubot.common.module.capability.GameChatBridge.class,
                    (org.windy.xingtubot.common.module.capability.GameChatBridge) (botEvent, content) ->
                            org.windy.xingtubot.bukkit.module.chatreply.listener.GuildMessageListener.broadcastToGame(
                                    this, botEvent.getFormId(), botEvent.getUsername(),
                                    // 群图片 → ChatImage 码：装 mod 的玩家看到图，没装的看到文本，游戏照常
                                    org.windy.xingtubot.common.util.ChatImageCode.appendTo(
                                            content, botEvent.getImageUrls(), botEvent.getUsername()),
                                    botEvent.getGuildId(),
                                    ChatreplyModule.getSensitiveFilter(),
                                    openid -> {
                                        org.windy.xingtubot.common.binding.BindingRepository repo =
                                                host.getService(org.windy.xingtubot.common.binding.BindingRepository.class);
                                        return repo != null ? repo.getPlayersByOpenid(openid)
                                                : java.util.Collections.emptyList();
                                    }));
        }

        // QQ→游戏：注册 GroupChatHandler（兜底广播，经上面的 GameChatBridge）；
        // 并注入机器人消息回显到游戏的平台广播器（线程安全：非主线程时调度回主线程）。
        ChatlinkModule chatlink = new ChatlinkModule(this).withGameBroadcaster(line -> {
            Runnable send = () -> {
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) p.sendMessage(line);
            };
            if (org.bukkit.Bukkit.isPrimaryThread()) send.run();
            else org.bukkit.Bukkit.getScheduler().runTask(this, send);
        });
        module = ExtensionBootstrap.enable(host, chatlink, config, logger, getDataFolder());

        // 游戏→QQ：ChatreplyModule（GameChatForwarder + /messagereply + 敏感词）
        if (config.getBoolean("chatreply-enable", true)) {
            PlayerLockManager lockState = host.getService(PlayerLockManager.class);
            chatreplyModule = new ChatreplyModule(this, config, logger, lockState);

            // 注入目标群 + 主动发送器（否则 game→QQ 永远不转发：默认 allowedGroups={"*"} 无具体群）
            chatreplyModule.getGameChatForwarder().setAllowedGroups(config.getStringList("allowed-groups"));
            // game→QQ 聊天行 markdown 模板（{player}/{message}）
            chatreplyModule.getGameChatForwarder().setGameFormat(config.getString("chatlink-format",
                    org.windy.xingtubot.common.util.ChatlinkFormat.DEFAULT));
            // 传「供给器」每次发送时现取——不能一次性 getService 缓存（onEnable 时核心可能还没注册 → 缓存 null 永不自愈）。
            chatreplyModule.setProactiveSender(
                    () -> host.getService(org.windy.xingtubot.common.module.capability.ProactiveSender.class));

            warnNoConcreteGroup(config);
            getLogger().info("[Chatlink] 游戏→QQ 转发 + [点击回复] 已启用");
        }
    }

    /** allowed-groups 没有具体群（空或仅 "*"）时，游戏→QQ 无法主动转发，给出明确警告。 */
    private void warnNoConcreteGroup(BotConfig config) {
        boolean hasConcrete = false;
        for (String g : config.getStringList("allowed-groups")) {
            if (g != null && !"*".equals(g) && !g.trim().isEmpty()) { hasConcrete = true; break; }
        }
        if (!hasConcrete) {
            getLogger().warning("[Chatlink] allowed-groups 未配置具体群（为空或 \"*\"），游戏聊天将无法主动转发到 QQ 群。");
            getLogger().warning("   请在 XingtuBot-Chatlink/config.yml 把 allowed-groups 配成具体群 openid（群里发「id」可查）。");
        }
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
    }
}
