package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.AbstractCommandHandler;
import org.windy.xingtubot.common.handler.impl.OpenIdCaptureHandler;
import org.windy.xingtubot.common.module.ModuleContextImpl;
import org.windy.xingtubot.common.module.capability.*;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.io.File;
import java.util.List;

/**
 * BungeeCord 端核心命令处理器。
 */
public class BungeeCordCommandHandler extends AbstractCommandHandler {

    private final OpenIdCaptureHandler openIdCapture;
    private final BotConfig config;

    public BungeeCordCommandHandler(ProxyServer proxy, java.util.logging.Logger javaLogger,
                                    BotConfig config, BungeeCordBridge bridge,
                                    org.windy.xingtubot.common.image.TextImageRenderer textImage,
                                    File dataDir) {
        super(config, new BungeeCordBotLogger(javaLogger), dataDir, proxy);
        this.config = config;

        // openid 捕获
        openIdCapture = new OpenIdCaptureHandler();
        openIdCapture.setConsoleLogger(m -> proxy.getLogger().info(m));
        getRegistry().register(openIdCapture);

        // GameChatBridge
        getHost().registerService(GameChatBridge.class, (GameChatBridge) (event, content) -> {
            BungeeCordGroupChatLink gcl = getHost().getService(BungeeCordGroupChatLink.class);
            if (gcl != null) gcl.onGroupMessage(event, senderNameOf(event), content);
        });
    }

    @Override
    protected void registerPlatformServices(ModuleContextImpl ctx, BotConfig config,
                                             BotLogger logger, File dataFolder, Object platform) {
        ProxyServer proxy = (ProxyServer) platform;
        ctx.registerService(ServerQuery.class, new BungeeCordServerQuery(proxy));
        // CrossServerConsole 由 bridge 注入，这里不注册（bridge 可能还没创建）
    }

    @Override
    protected PlaceholderResolver createPlaceholders(BotConfig config, Object platform) {
        ProxyServer proxy = (ProxyServer) platform;
        return new BungeeCordPlaceholders(proxy, null,
                config.getString("entries-Empty", "群成员"));
    }

    public void startCaptureOpenid() { openIdCapture.enableCapture(); }

    public void handle(BotMessageEvent event) {
        handle(event, getPermission().isAdmin(event.getFormId()));
    }

    private String senderNameOf(BotMessageEvent event) {
        return senderNameOf(event, config.getString("entries-Empty", "群成员"));
    }
}
