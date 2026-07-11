package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
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
 * Velocity 端核心命令处理器。
 */
public class BotCommandHandler {

    private final HandlerRegistry registry;
    private final OpenIdCaptureHandler openIdCapture;
    private final BotConfig config;
    private final org.windy.xingtubot.common.api.XingtuBotServiceImpl service;
    private final ModuleContextImpl moduleCtx;
    private final LazyProactiveSender proactiveSender = new LazyProactiveSender();

    public BotCommandHandler(ProxyServer proxy, org.slf4j.Logger slf4jLogger,
                             BotConfig config, VelocityBridge bridge,
                             TextImageRenderer textImage, java.nio.file.Path dataDir) {
        this.config = config;

        PermissionService permission = new PermissionService(resolveAdminUids(config));
        registry = new HandlerRegistry(permission,
                m -> proxy.getConsoleCommandSource().sendMessage(Component.text("[群指令] " + m)));
        registry.setListenMode(config.getString("listen-mode", "mention"));

        org.windy.xingtubot.common.platform.BotLogger botLogger =
                new VelocityBotLogger(slf4jLogger);

        moduleCtx = new ModuleContextImpl(
                registry, config, botLogger, null, permission,
                dataDir != null ? dataDir.toFile() : null);

        // ===== openid 捕获 =====
        openIdCapture = new OpenIdCaptureHandler();
        openIdCapture.setConsoleLogger(m -> proxy.getConsoleCommandSource().sendMessage(Component.text(m)));
        registry.register(openIdCapture);

        // ===== 内置核心命令：「id」回显用户/群 ID =====
        registry.register(new org.windy.xingtubot.common.handler.impl.WhoAmIHandler());

        // ===== 平台能力注册 =====
        if (textImage != null) moduleCtx.registerService(TextImageRenderer.class, textImage);
        moduleCtx.registerService(ProactiveSender.class, proactiveSender);

        moduleCtx.registerService(PlaceholderResolver.class,
                new VelocityPlaceholders(proxy, null, bridge,
                        config.getString("entries-Empty", "群成员")));

        if (bridge != null) {
            moduleCtx.registerService(CrossServerConsole.class,
                    (CrossServerConsole) (server, cmd, callback) -> bridge.dispatchConsole(server, cmd, callback));
        }

        moduleCtx.registerService(ServerQuery.class, new VelocityServerQuery(proxy));

        moduleCtx.registerService(GameChatBridge.class, (GameChatBridge) (event, content) -> {
            GroupChatLink gcl = moduleCtx.getService(GroupChatLink.class);
            if (gcl != null) {
                String withImg = org.windy.xingtubot.common.util.ChatImageCode.appendTo(
                        content, event.getImageUrls(), event.getUsername());
                gcl.onGroupMessage(event, senderNameOf(event), withImg);
            }
        });

        // 对外 API
        service = new org.windy.xingtubot.common.api.XingtuBotServiceImpl(null);
        service.setRegistry(registry);
        registry.setHookService(service);
        moduleCtx.registerService(org.windy.xingtubot.common.api.XingtuBotService.class, service);

        // 初始化 handler
        HandlerContext ctx = new HandlerContext(config, null, permission, proxy);
        registry.initAll(ctx);
    }

    public org.windy.xingtubot.common.api.XingtuBotServiceImpl getService() { return service; }
    public ModuleContextImpl getHost() { return moduleCtx; }
    public LazyProactiveSender getProactiveSender() { return proactiveSender; }

    public void shutdown() { registry.shutdownAll(); }

    public void startCaptureOpenid() { openIdCapture.enableCapture(); }

    public void handle(BotMessageEvent event) {
        String msg = event.getMessage();
        if ((msg == null || msg.trim().isEmpty()) && event.getImageUrls().isEmpty()) return;
        String t = msg == null ? "" : msg.trim();
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
        if (bs != null) {
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
