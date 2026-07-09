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

        PermissionService permission = new PermissionService(config.getStringList("admin-openids"));
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

        // ===== 内置核心命令：「id」回显用户/群 openid（配置辅助）=====
        registry.register(new org.windy.xingtubot.common.handler.impl.WhoAmIHandler());

        // 平台能力注册
        if (textImage != null) moduleCtx.registerService(TextImageRenderer.class, textImage);
        moduleCtx.registerService(ProactiveSender.class, proactiveSender);

        // 机器人消息回显到游戏（GameEcho 服务 + 命令回复回显）已下放到 xt-chatlink（群服互联范畴）。

        // BindingService/BindingRepository 由 xt-auth 附属注册为 service，这里不再创建/注册（与 Velocity 一致）。
        // Placeholder 的绑定查询走惰性：传 null，绑定名由 senderNameOf 在用时从 host 取。
        moduleCtx.registerService(PlaceholderResolver.class,
                new BungeeCordPlaceholders(proxy, null, bridge,
                        config.getString("entries-Empty", "群成员")));

        if (bridge != null) {
            moduleCtx.registerService(CrossServerConsole.class,
                    (CrossServerConsole) (server, cmd, callback) -> bridge.dispatchConsole(server, cmd, callback));
        }

        moduleCtx.registerService(ServerQuery.class, new BungeeCordServerQuery(proxy));

        // GameChatBridge：惰性解析 BungeeCordGroupChatLink（由 xt-chatlink 扩展注册为 service）
        moduleCtx.registerService(GameChatBridge.class, (GameChatBridge) (event, content) -> {
            BungeeCordGroupChatLink gcl = moduleCtx.getService(BungeeCordGroupChatLink.class);
            if (gcl != null) gcl.onGroupMessage(event, senderNameOf(event), content);
        });

        // 对外 API（玩家绑定查询已下放到 xt-auth，核心 API 不再持有 bindingStore）
        service = new org.windy.xingtubot.common.api.XingtuBotServiceImpl(null);
        service.setRegistry(registry);
        registry.setHookService(service);
        // 注册进服务总线：xt-auth 经 ctx.getService(XingtuBotService.class) 取它读 appId（同 Velocity 修复）。
        moduleCtx.registerService(org.windy.xingtubot.common.api.XingtuBotService.class, service);

        // 初始化 handler
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
        // 菜单走自定义回复（replies.yml 里 trigger=菜单, content={menu}）；先派发。
        boolean handled = registry.dispatch(event);
        // 兜底：replies.yml 没配 菜单 条目时，仍用 buildMenu 生成全部命令菜单。
        if (!handled && (t.equals("菜单") || t.equals("帮助") || t.equalsIgnoreCase("help"))) {
            boolean isAdmin = new PermissionService(config.getStringList("admin-openids")).isAdmin(event.getFormId());
            event.replyMarkdown(registry.buildMenu(isAdmin), null);
        }
        String pending = PendingMessageQueue.getInstance().drainForGroup(event.getGuildId());
        if (pending != null) event.reply(pending);
    }

    /** 惰性获取发送者名：从 host 取 BindingService（由 xt-auth 注册），与 Velocity 一致。 */
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
}
