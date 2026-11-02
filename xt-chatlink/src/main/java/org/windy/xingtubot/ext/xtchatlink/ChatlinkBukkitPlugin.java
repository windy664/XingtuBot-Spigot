package org.windy.xingtubot.ext.xtchatlink;

import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.module.chatreply.ChatreplyModule;
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
    private org.windy.xingtubot.common.chatlink.ChatImageTracker imageTracker;

    @Override
    public void onEnable() {
        XingtuBotHost host = getServer().getServicesManager().load(XingtuBotHost.class);

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        // ===== ChatImage 模组探测（复用 xt-auth 内置并初始化的 PacketEvents，绝不自行 build/init）=====
        imageTracker = new org.windy.xingtubot.common.chatlink.ChatImageTracker();
        imageTracker.setMode(org.windy.xingtubot.common.chatlink.ChatImageTracker
                .parseMode(config.getString("chatlink-image-format", "auto")));
        if (!org.windy.xingtubot.chatlink.detect.ChatImageDetection.tryAttach(imageTracker, logger)
                && imageTracker.getMode() == org.windy.xingtubot.common.chatlink.ChatImageTracker.Mode.AUTO) {
            // 无探测能力（未装 xt-auth）：AUTO 回退为全 CICode，保持旧行为
            imageTracker.setMode(org.windy.xingtubot.common.chatlink.ChatImageTracker.Mode.CICODE);
        }
        // 玩家退服清标记（频道注册每次入服重新广播，重进即恢复探测）
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                imageTracker.forget(e.getPlayer().getUniqueId());
            }
        }, this);

        // QQ→游戏：注册 GameChatBridge（用 xt-chatlink 自己的 GuildMessageListener，带 group openid 拼回复按钮）。
        // 必须在 ExtensionBootstrap.enable 之前注册——ChatlinkModule.onEnable 会 getService(GameChatBridge) 拿它。
        if (host != null) {
            final org.windy.xingtubot.common.chatlink.ChatImageTracker tracker = imageTracker;
            host.registerService(ChatlinkModule.GAME_BRIDGE_SERVICE,
                    (java.util.function.BiConsumer<org.windy.xingtubot.common.event.BotMessageContext, String>) (botEvent, content) ->
                            org.windy.xingtubot.bukkit.module.chatreply.listener.GuildMessageListener.broadcastToGame(
                                    this, botEvent.getSenderId(), botEvent.getUsername(),
                                    // 正文原样传入；图片由广播器按玩家分流（装 ChatImage → CICode，未装 → [图片] 可点击）
                                    content, botEvent.getImageUrls(), tracker,
                                    botEvent.getConversationId(),
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
            org.windy.xingtubot.common.service.SensitiveFilter sf =
                    host.getService(org.windy.xingtubot.common.service.SensitiveFilter.class);
            chatreplyModule = new ChatreplyModule(this, config, logger, lockState, sf);

            // 注入目标群 + 主动发送器（否则 game→QQ 永远不转发：默认 allowedGroups={"*"} 无具体群）
            java.util.List<String> allowedGroups = org.windy.xingtubot.common.util.GroupTargets
                    .resolveConcrete(host, config.getStringList("allowed-groups"));
            chatreplyModule.getGameChatForwarder().setAllowedGroups(allowedGroups);
            // game→QQ 聊天行 markdown 模板（{player}/{message}）
            chatreplyModule.getGameChatForwarder().setGameFormat(config.getString("chatlink-format",
                    org.windy.xingtubot.chatlink.util.ChatlinkFormat.DEFAULT));
            // 传「供给器」每次发送时现取——不能一次性 getService 缓存（onEnable 时核心可能还没注册 → 缓存 null 永不自愈）。
            chatreplyModule.setProactiveSender(
                    () -> host.getService(org.windy.xingtubot.common.module.capability.ProactiveSender.class));

            warnNoConcreteGroup(allowedGroups);
            getLogger().info("[Chatlink] 游戏→QQ 转发 + [点击回复] 已启用");
        }
    }

    /** allowed-groups 没有具体群（空或仅 "*"）时，游戏→QQ 无法主动转发，给出明确警告。 */
    private void warnNoConcreteGroup(java.util.List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            getLogger().warning("[Chatlink] allowed-groups 未配置具体群（为空或 \"*\"），游戏聊天将无法主动转发到 QQ 群。");
            getLogger().warning("   请在 XingtuBot-Chatlink/config.yml 把 allowed-groups 配成具体群 openid（群里发「id」可查）。");
        }
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
        org.windy.xingtubot.chatlink.detect.ChatImageDetection.detach();
    }
}
