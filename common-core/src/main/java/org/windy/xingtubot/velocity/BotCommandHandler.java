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
 *
 * <p>只做框架：注册平台能力服务 → 暴露 XingtuBotHost → 注册对外 API → 群消息分发。
 * 一切功能由附属扩展插件（xt-*）提供。
 * BindingService/BindingRepository 由 xt-auth 注册为 service，这里不再创建。
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

        PermissionService permission = new PermissionService(config.getStringList("admin-openids"));
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

        // ===== 内置核心命令：「id」回显用户/群 openid（配置辅助）=====
        registry.register(new org.windy.xingtubot.common.handler.impl.WhoAmIHandler());

        // ===== 平台能力注册（供附属插件 getService 获取）=====
        if (textImage != null) moduleCtx.registerService(TextImageRenderer.class, textImage);
        moduleCtx.registerService(ProactiveSender.class, proactiveSender);

        // 机器人消息回显到游戏（GameEcho 服务 + 命令回复回显）已下放到 xt-chatlink（群服互联范畴）。

        // PlaceholderResolver：惰性获取 BindingService（由 xt-auth 注册）
        moduleCtx.registerService(PlaceholderResolver.class,
                new VelocityPlaceholders(proxy, null, bridge,
                        config.getString("entries-Empty", "群成员")));

        // BindingService / BindingRepository 由 xt-auth 扩展插件注册为 service，主插件不再创建

        if (bridge != null) {
            moduleCtx.registerService(CrossServerConsole.class,
                    (CrossServerConsole) (server, cmd, callback) -> bridge.dispatchConsole(server, cmd, callback));
        }

        moduleCtx.registerService(ServerQuery.class, new VelocityServerQuery(proxy));

        // GameChatBridge：惰性解析 GroupChatLink + BindingService（由扩展插件注册为 service）
        moduleCtx.registerService(GameChatBridge.class, (GameChatBridge) (event, content) -> {
            GroupChatLink gcl = moduleCtx.getService(GroupChatLink.class);
            if (gcl != null) {
                // 群图片 → ChatImage 码：装 mod 的玩家看到图，没装的看到文本，游戏照常
                String withImg = org.windy.xingtubot.common.util.ChatImageCode.appendTo(
                        content, event.getImageUrls(), event.getUsername());
                gcl.onGroupMessage(event, senderNameOf(event), withImg);
            }
        });

        // 对外 API
        service = new org.windy.xingtubot.common.api.XingtuBotServiceImpl(null);
        service.setRegistry(registry);
        registry.setHookService(service);
        // 注册进服务总线：附属插件（如 xt-auth）经 ctx.getService(XingtuBotService.class) 取它读 appId。
        // Velocity 此前漏了这步（Bukkit 在 SpigotCommandHandler 走 ServicesManager 注册过）→ xt-auth 取到
        // null → getBotAppId() 空 → 绑定 openid 头像 URL 落空（表现为 APPID_EMPTY / 企鹅占位图）。
        // 此处注册的是同一 service 实例，appId 稍后由 XingtuBotVelocity.setApiClient 填入；xt-auth 惰性现取即可。
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
        // 纯图片消息正文为空但带图也要放行（转发进游戏）；文字、图片都空才丢
        if ((msg == null || msg.trim().isEmpty()) && event.getImageUrls().isEmpty()) return;
        String t = msg == null ? "" : msg.trim();
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

    /** 惰性获取发送者名：从 service 获取 BindingService（由 xt-auth 注册）。 */
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
}
