package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import org.windy.xingtubot.common.auth.PermissionService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.HandlerRegistry;
import org.windy.xingtubot.common.handler.impl.OpenIdCaptureHandler;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.module.ModuleContextImpl;
import org.windy.xingtubot.common.module.capability.*;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.util.List;

/**
 * BungeeCord 端核心命令处理器。与 BotCommandHandler (Velocity) 功能对等。
 */
public class BungeeCordCommandHandler {

    private final HandlerRegistry registry;
    private final OpenIdCaptureHandler openIdCapture;
    private final BotConfig config;
    private final org.windy.xingtubot.common.api.XingtuBotServiceImpl service;
    private final ModuleContextImpl moduleCtx;
    private final LazyProactiveSender proactiveSender = new LazyProactiveSender();

    public BungeeCordCommandHandler(ProxyServer proxy, java.util.logging.Logger javaLogger,
                                    BotConfig config,
                                    BungeeCordBridge bridge,
                                    TextImageRenderer textImage, java.io.File dataDir) {
        this.config = config;

        PermissionService permission = new PermissionService(resolveAdminUids(config));
        registry = new HandlerRegistry(permission,
                m -> proxy.getLogger().info("[群指令] " + m));
        registry.setListenMode(config.getString("listen-mode", "mention"));

        BungeeCordBotLogger botLogger = new BungeeCordBotLogger(javaLogger);

        moduleCtx = new ModuleContextImpl(
                registry, config, botLogger, null, permission,
                dataDir);

        // openid 捕获
        openIdCapture = new OpenIdCaptureHandler();
        openIdCapture.setConsoleLogger(m -> proxy.getLogger().info(m));
        registry.register(openIdCapture);

        registry.register(new org.windy.xingtubot.common.handler.impl.WhoAmIHandler());

        // 平台能力注册
        if (textImage != null) moduleCtx.registerService(TextImageRenderer.class, textImage);
        moduleCtx.registerService(ProactiveSender.class, proactiveSender);

        moduleCtx.registerService(PlaceholderResolver.class,
                new BungeeCordPlaceholders(proxy, null, bridge,
                        config.getString("entries-Empty", "群成员")));

        if (bridge != null) {
            moduleCtx.registerService(CrossServerConsole.class,
                    (CrossServerConsole) (server, cmd, callback) -> bridge.dispatchConsole(server, cmd, callback));
        }

        moduleCtx.registerService(ServerQuery.class, new BungeeCordServerQuery(proxy));

        moduleCtx.registerService(GameChatBridge.class, (GameChatBridge) (event, content) -> {
            BungeeCordGroupChatLink gcl = moduleCtx.getService(BungeeCordGroupChatLink.class);
            if (gcl != null) gcl.onGroupMessage(event, senderNameOf(event), content);
        });

        // 对外 API
        service = new org.windy.xingtubot.common.api.XingtuBotServiceImpl(null);
        service.setRegistry(registry);
        registry.setHookService(service);
        moduleCtx.registerService(org.windy.xingtubot.common.api.XingtuBotService.class, service);

        HandlerContext ctx = new HandlerContext(config, null, permission, null);
        registry.initAll(ctx);
    }

    public org.windy.xingtubot.common.api.XingtuBotServiceImpl getService() { return service; }
    public ModuleContextImpl getHost() { return moduleCtx; }
    public LazyProactiveSender getProactiveSender() { return proactiveSender; }

    public void shutdown() { registry.shutdownAll(); }
    public void startCaptureOpenid() { openIdCapture.enableCapture(); }

    public void handle(BotMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.trim().isEmpty()) return;
        String t = msg.trim();
        boolean handled = registry.dispatch(event);
        if (!handled && (t.equals("菜单") || t.equals("帮助") || t.equalsIgnoreCase("help"))) {
            boolean isAdmin = new PermissionService(resolveAdminUids(config)).isAdmin(event.getSenderUid());
            event.replyMarkdown(registry.buildMenu(isAdmin), null);
        }
        String pending = PendingMessageQueue.getInstance().drainForGroup(event.getSessionId());
        if (pending != null) event.reply(pending);
    }

    private String senderNameOf(BotMessageEvent event) {
        org.windy.xingtubot.common.binding.BindingService bs =
                moduleCtx.getService(org.windy.xingtubot.common.binding.BindingService.class);
        if (bs != null && event.getFormId() != null) {
            List<String> players = bs.getStore().getPlayersByOpenid(event.getFormId());
            if (!players.isEmpty()) return players.get(0);
        }
        if (event.getUsername() != null && !event.getUsername().isEmpty()) return event.getUsername();
        return config.getString("entries-Empty", "群成员");
    }

    /** 兼容新旧配置键：admin-uids（新）和 admin-openids（旧）。 */
    private static List<String> resolveAdminUids(BotConfig config) {
        List<String> uids = config.getStringList("admin-uids");
        if (uids.isEmpty()) {
            uids = config.getStringList("admin-openids");
        }
        return uids;
    }
}
