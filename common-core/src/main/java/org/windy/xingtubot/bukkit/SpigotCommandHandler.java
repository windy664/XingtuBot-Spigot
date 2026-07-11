package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.windy.xingtubot.common.event.BotMessageEvent.MessageType;
import org.bukkit.event.EventPriority;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.api.XingtuBotService;
import org.windy.xingtubot.common.api.XingtuBotServiceImpl;
import org.windy.xingtubot.common.auth.PermissionService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.HandlerContext;

import java.util.List;
import org.windy.xingtubot.common.handler.HandlerRegistry;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.module.ModuleContextImpl;
import org.windy.xingtubot.common.module.capability.*;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

/**
 * Bukkit 端核心命令中心。
 *
 * <p>只做框架：注册平台能力服务 → 暴露 XingtuBotHost → 注册对外 API → 监听群消息分发。
 * 一切功能由附属扩展插件（xt-*）经 {@link org.windy.xingtubot.common.module.ExtensionBootstrap} 注册。
 */
public class SpigotCommandHandler implements Listener {

    private final HandlerRegistry registry;
    private final XingtuBot plugin;
    private XingtuBotServiceImpl xingtuService;
    private final LazyProactiveSender proactiveSender = new LazyProactiveSender();
    private final PermissionService permission;
    private final ModuleContextImpl moduleCtx;

    public SpigotCommandHandler(XingtuBot plugin) {
        this.plugin = plugin;
        BotConfig config = new SpigotConfig(plugin.getConfig());
        SpigotBotLogger logger = new SpigotBotLogger(plugin.getLogger());
        // 同时兼容新旧配置键：admin-uids（新）和 admin-openids（旧）
        List<String> adminUids = config.getStringList("admin-uids");
        if (adminUids.isEmpty()) {
            adminUids = config.getStringList("admin-openids"); // 兼容旧配置
        }
        PermissionService permission = new PermissionService(adminUids);
        this.permission = permission;

        registry = new HandlerRegistry(permission, m -> plugin.getLogger().info("[群指令] " + m));
        registry.setListenMode(config.getString("listen-mode", "mention"));

        // 模块上下文（共享服务总线，供附属扩展插件接入）
        moduleCtx = new ModuleContextImpl(
                registry, config, logger, null, permission, plugin.getDataFolder());

        // ===== 平台能力注册（供附属插件 getService 获取）=====

        // 控制台命令执行器
        moduleCtx.registerService(ConsoleExecutor.class, (ConsoleExecutor) (cmd, callback) -> {
            org.windy.xingtubot.bukkit.module.console.CapturingConsoleSender sender =
                    new org.windy.xingtubot.bukkit.module.console.CapturingConsoleSender(callback);
            Bukkit.getScheduler().runTask(plugin, () -> {
                try { Bukkit.dispatchCommand(sender, cmd); }
                catch (Exception e) { callback.accept("⚠️ 命令异常: " + e.getMessage()); return; }
                sender.flushDelayed(plugin);
            });
        });

        // 玩家命令执行器
        moduleCtx.registerService(PlayerCommandExecutor.class, (PlayerCommandExecutor) (player, cmd, callback) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.entity.Player p = Bukkit.getPlayerExact(player);
                if (p != null && p.isOnline()) {
                    try { Bukkit.dispatchCommand(p, cmd); callback.accept("✅ 已作为 " + player + " 执行: " + cmd); }
                    catch (Exception e) { callback.accept("⚠️ 命令异常: " + e.getMessage()); }
                } else { callback.accept("玩家 " + player + " 不在线"); }
            }));

        // 文字生图
        java.io.File fontFile = new java.io.File(plugin.getDataFolder(), "font.ttf");
        java.io.File templateDir = new java.io.File(plugin.getDataFolder(), "templates");
        templateDir.mkdirs();
        moduleCtx.registerService(TextImageRenderer.class, new TextImageRenderer(fontFile, templateDir));

        // 占位符
        SpigotPlaceholders spigotPlaceholders = new SpigotPlaceholders(
                null, config.getString("entries-Empty", "群成员"));
        spigotPlaceholders.registerPapiExpansion();
        moduleCtx.registerService(PlaceholderResolver.class, spigotPlaceholders);

        // 主动消息（惰性句柄，bot ready 后由 XingtuBot 填实）
        moduleCtx.registerService(ProactiveSender.class, proactiveSender);

        // 机器人消息回显到游戏（GameEcho 服务 + 命令回复回显）已下放到 xt-chatlink（群服互联范畴）。

        // 群服互联 QQ→游戏 的桥（GameChatBridge）由 xt-chatlink 自己注册（含 [点击回复] 按钮 + group openid），
        // 主插件不再提供，避免与扩展重复实现。

        // ===== 暴露宿主，供附属扩展插件接入 =====
        Bukkit.getServicesManager().register(
                org.windy.xingtubot.common.module.XingtuBotHost.class, moduleCtx,
                plugin, org.bukkit.plugin.ServicePriority.Normal);
        plugin.getLogger().info("[核心] XingtuBotHost 已注册（供附属扩展插件接入）");

        // ===== 注册 GuildMessageEvent 监听 =====
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // ===== 内置核心命令：「id」回显用户/群 openid（配置辅助）=====
        registry.register(new org.windy.xingtubot.common.handler.impl.WhoAmIHandler());

        // ===== 初始化所有 handler =====
        HandlerContext ctx = new HandlerContext(config, logger, permission, plugin);
        registry.initAll(ctx);

        // ===== 注册对外 API =====
        XingtuBotServiceImpl xingtuApi = new XingtuBotServiceImpl(null);
        xingtuApi.setRegistry(registry);
        this.xingtuService = xingtuApi;
        registry.setHookService(xingtuApi);
        Bukkit.getServicesManager().register(
                XingtuBotService.class, xingtuApi, plugin, org.bukkit.plugin.ServicePriority.Normal);
        plugin.getLogger().info("[核心] XingtuBotService API 已注册");

        plugin.getLogger().info("[核心] 命令中心已就绪（功能由附属扩展插件提供）");
    }

    /** 宿主能力面（含部署拓扑 isBrain），供主类注入 setBrain。 */
    public ModuleContextImpl getHost() {
        return moduleCtx;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGuildMessage(GuildMessageEvent event) {
        String msg = event.getMessage();
        // 纯图片消息正文为空但带图也要放行（转发进游戏）；文字、图片都空才丢
        if ((msg == null || msg.trim().isEmpty()) && event.getImageUrls().isEmpty()) return;
        if (msg == null) msg = "";
        String pending = PendingMessageQueue.getInstance().drainForGroup(event.getgroupId());
        if (pending != null) event.reply(pending);
        // 从 Bukkit 的 GuildMessageEvent 还原为平台无关的 BotMessageEvent
        // 当前双端 dialog：formId == senderUid（单协议下值相等）
        String groupId = event.getgroupId(); // GuildMessageEvent 向后兼容保留 groupId 命名
        String senderUid = event.getFormId();
        MessageType msgType = event.getgroupId() != null ? MessageType.GROUP : MessageType.PRIVATE;
        BotMessageEvent botEvent = new BotMessageEvent(
                groupId, event.getFormId(), senderUid,
                msg, event.getReplier(), event.getUsername(),
                msgType, event.getEventType());
        botEvent.setImageUrls(event.getImageUrls());
        boolean handled = registry.dispatch(botEvent);
        // 兜底：replies.yml 没配 菜单 条目时，仍用 buildMenu 生成全部命令菜单。
        String t = msg.trim();
        if (!handled && (t.equals("菜单") || t.equals("帮助") || t.equalsIgnoreCase("help"))) {
            botEvent.replyMarkdown(registry.buildMenu(permission.isAdmin(senderUid)), null);
        }
    }

    public void shutdown() {
        registry.shutdownAll();
    }

    public LazyProactiveSender getProactiveSender() { return proactiveSender; }
    public XingtuBotServiceImpl getXingtuService() { return xingtuService; }
    public HandlerRegistry getRegistry() { return registry; }
}
