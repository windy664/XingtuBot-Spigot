package org.windy.xingtubot.common.handler;

import org.windy.xingtubot.common.runtime.XingtuBotServiceImpl;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessage;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.module.ModuleContextImpl;
import org.windy.xingtubot.common.module.capability.LazyProactiveSender;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.io.File;

/**
 * 三端通用的命令中心基类。构造流程、handle()、API 注册全在基类，
 * 平台差异通过 {@link #registerPlatformServices} 钩子隔离。
 */
public abstract class AbstractCommandHandler {

    private final HandlerRegistry registry;
    private final PermissionChecker permission;
    private final ModuleContextImpl moduleCtx;
    private final XingtuBotServiceImpl service;
    private final LazyProactiveSender proactiveSender = new LazyProactiveSender();

    protected AbstractCommandHandler(BotConfig config, BotLogger logger, File dataFolder,
                                     Object platformContext) {
        this.permission = (openid -> config.getStringList("admin-openids").contains(openid));

        registry = new HandlerRegistry(permission, m -> logger.info("[群指令] " + m));
        registry.setListenMode(config.getString("listen-mode", "mention"));

        moduleCtx = new ModuleContextImpl(registry, config, logger, null, permission, dataFolder);

        // ===== 通用服务注册 =====
        if (dataFolder != null) {
            File fontFile = new File(dataFolder, "font.ttf");
            File templateDir = new File(dataFolder, "templates");
            templateDir.mkdirs();
            moduleCtx.registerService(TextImageRenderer.class, new TextImageRenderer(fontFile, templateDir));
        }
        moduleCtx.registerService(ProactiveSender.class, proactiveSender);

        // ===== 平台特定服务注册 =====
        registerPlatformServices(moduleCtx, config, logger, dataFolder, platformContext);

        // ===== 内置 handler =====
        registry.register(new org.windy.xingtubot.common.handler.impl.WhoAmIHandler());

        // ===== Placeholder =====
        PlaceholderResolver placeholders = createPlaceholders(config, platformContext);
        if (placeholders != null) {
            moduleCtx.registerService(PlaceholderResolver.class, placeholders);
        }

        // ===== 对外 API =====
        service = new XingtuBotServiceImpl(null);
        service.setRegistry(registry);
        registry.setHookService(service);
        moduleCtx.registerService(org.windy.xingtubot.common.api.XingtuBotService.class, service);
        moduleCtx.registerService(org.windy.xingtubot.common.api.CommandRegistrar.class, service);
        moduleCtx.registerService(org.windy.xingtubot.common.api.MessageSender.class, service);
        moduleCtx.registerService(org.windy.xingtubot.common.api.CommandHookBus.class, service);
        moduleCtx.registerService(org.windy.xingtubot.common.api.BotRuntimeInfo.class, service);

        // ===== 初始化 =====
        HandlerContext ctx = new HandlerContext(config, logger, permission, platformContext);
        registry.initAll(ctx);
    }

    /** 平台特定服务注册（ConsoleExecutor / CrossServerConsole / ServerQuery 等）。 */
    protected abstract void registerPlatformServices(ModuleContextImpl ctx, BotConfig config,
                                                      BotLogger logger, File dataFolder,
                                                      Object platformContext);

    /** 创建平台特定的 PlaceholderResolver。返回 null 表示不注册。 */
    protected abstract PlaceholderResolver createPlaceholders(BotConfig config, Object platformContext);

    // ==================== 消息处理 ====================

    /**
     * 处理群消息。三端共用逻辑：dispatch + 菜单兜底 + pending messages。
     * @param event 群消息事件
     * @param senderIsAdmin 发送者是否管理员
     */
    public void handle(BotMessageEvent event, boolean senderIsAdmin) {
        String msg = event.getMessage();
        if ((msg == null || msg.trim().isEmpty()) && event.getImageUrls().isEmpty()) return;
        String t = msg == null ? "" : msg.trim();

        boolean handled = registry.dispatch(event);

        // 兜底菜单
        if (!handled && (t.equals("菜单") || t.equals("帮助") || t.equalsIgnoreCase("help"))) {
            event.replyMarkdown(registry.buildMenu(senderIsAdmin), null);
        }

        // pending messages
        String pending = PendingMessageQueue.getInstance().drainForGroup(event.getConversationId());
        if (pending != null) event.reply(pending);
    }

    // ==================== 访问器 ====================

    public ModuleContextImpl getHost() { return moduleCtx; }
    public HandlerRegistry getRegistry() { return registry; }
    public LazyProactiveSender getProactiveSender() { return proactiveSender; }
    public XingtuBotServiceImpl getService() { return service; }
    public PermissionChecker getPermission() { return permission; }

    public void shutdown() { registry.shutdownAll(); }

    /**
     * 惰性获取发送者名：从服务总线反射查 BindingRepository（由 xt-auth 注册）。
     * 三端共用，避免 Velocity/BungeeCord 各抄一份。
     */
    protected String senderNameOf(BotMessage event, String defaultSender) {
        try {
            Object bs = moduleCtx.getServiceObject(Class.forName("org.windy.xingtubot.common.binding.BindingService"));
            if (bs != null && event.getSenderId() != null) {
                Object store = bs.getClass().getMethod("getStore").invoke(bs);
                if (store != null) {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> players = (java.util.List<String>) store.getClass()
                            .getMethod("getPlayersByOpenid", String.class).invoke(store, event.getSenderId());
                    if (players != null && !players.isEmpty()) return players.get(0);
                }
            }
        } catch (Exception ignored) {
        }
        if (event.getUsername() != null && !event.getUsername().isEmpty()) return event.getUsername();
        return defaultSender;
    }
}
