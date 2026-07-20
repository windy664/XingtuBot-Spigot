package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.ServicePriority;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.bukkit.module.console.CapturingConsoleSender;
import org.windy.xingtubot.common.api.XingtuBotService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.AbstractCommandHandler;
import org.windy.xingtubot.common.module.ModuleContextImpl;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.capability.*;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.io.File;

/**
 * Bukkit 端核心命令中心。平台差异通过 {@link #registerPlatformServices} 钩子注入。
 */
public class SpigotCommandHandler extends AbstractCommandHandler implements Listener {

    private final XingtuBot plugin;

    public SpigotCommandHandler(XingtuBot plugin) {
        super(new SpigotConfig(plugin.getConfig()),
                new SpigotBotLogger(plugin.getLogger()),
                plugin.getDataFolder(),
                plugin);
        this.plugin = plugin;

        Bukkit.getServicesManager().register(XingtuBotHost.class, getHost(), plugin, ServicePriority.Normal);
        Bukkit.getServicesManager().register(XingtuBotService.class, getService(), plugin, ServicePriority.Normal);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[核心] 命令中心已就绪（功能由附属扩展插件提供）");
    }

    @Override
    protected void registerPlatformServices(ModuleContextImpl ctx, BotConfig config,
                                             BotLogger logger, File dataFolder, Object platform) {
        XingtuBot plugin = (XingtuBot) platform;

        ctx.registerService(ConsoleExecutor.class, (ConsoleExecutor) (cmd, callback) -> {
            CapturingConsoleSender sender = new CapturingConsoleSender(callback);
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(sender, cmd);
                } catch (Exception e) {
                    callback.accept("⚠️ 命令异常: " + e.getMessage());
                    return;
                }
                sender.flushDelayed(plugin);
            });
        });

        ctx.registerService(PlayerCommandExecutor.class, (PlayerCommandExecutor) (player, cmd, callback) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayerExact(player);
                if (p != null && p.isOnline()) {
                    try {
                        Bukkit.dispatchCommand(p, cmd);
                        callback.accept("✅ 已作为 " + player + " 执行: " + cmd);
                    } catch (Exception e) {
                        callback.accept("⚠️ 命令异常: " + e.getMessage());
                    }
                } else {
                    callback.accept("玩家 " + player + " 不在线");
                }
            }));
    }

    @Override
    protected PlaceholderResolver createPlaceholders(BotConfig config, Object platform) {
        SpigotPlaceholders ph = new SpigotPlaceholders(null);
        ph.registerPapiExpansion();
        return ph;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGuildMessage(GuildMessageEvent event) {
        String msg = event.getMessage();
        if ((msg == null || msg.trim().isEmpty()) && event.getImageUrls().isEmpty()) return;
        if (msg == null) msg = "";
        BotMessageEvent botEvent = new BotMessageEvent(
                event.getConversationId(), event.getSenderId(), msg,
                event.getReply(), event.getUsername(), event.getEventType());
        botEvent.setImageUrls(event.getImageUrls());
        handle(botEvent, getPermission().isAdmin(event.getSenderId()));
    }
}
