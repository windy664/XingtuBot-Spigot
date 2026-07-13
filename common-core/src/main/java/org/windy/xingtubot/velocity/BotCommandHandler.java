package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.AbstractCommandHandler;
import org.windy.xingtubot.common.handler.impl.OpenIdCaptureHandler;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.module.ModuleContextImpl;
import org.windy.xingtubot.common.module.capability.*;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.io.File;
import java.nio.file.Path;

/**
 * Velocity 端核心命令处理器。
 */
public class BotCommandHandler extends AbstractCommandHandler {

    private final OpenIdCaptureHandler openIdCapture;
    private final BotConfig config;

    public BotCommandHandler(ProxyServer proxy, org.slf4j.Logger slf4jLogger,
                             BotConfig config, VelocityBridge bridge,
                             TextImageRenderer textImage, Path dataDir) {
        super(config, new VelocityBotLogger(slf4jLogger),
                dataDir != null ? dataDir.toFile() : null, proxy);
        this.config = config;

        // openid 捕获
        openIdCapture = new OpenIdCaptureHandler();
        openIdCapture.setConsoleLogger(m -> proxy.getConsoleCommandSource().sendMessage(Component.text(m)));
        getRegistry().register(openIdCapture);

        // GameChatBridge
        getHost().registerService(GameChatBridge.class, (GameChatBridge) (event, content) -> {
            GroupChatLink gcl = getHost().getService(GroupChatLink.class);
            if (gcl != null) {
                String withImg = org.windy.xingtubot.common.util.ChatImageCode.appendTo(
                        content, event.getImageUrls(), event.getUsername());
                gcl.onGroupMessage(event, senderNameOf(event), withImg);
            }
        });
    }

    @Override
    protected void registerPlatformServices(ModuleContextImpl ctx, BotConfig config,
                                             BotLogger logger, File dataFolder, Object platform) {
        ProxyServer proxy = (ProxyServer) platform;

        ctx.registerService(ServerQuery.class, new VelocityServerQuery(proxy));

        // CrossServerConsole：经 bridge 跨服执行命令
        // bridge 在构造时可能还没创建，这里用惰性获取
    }

    @Override
    protected PlaceholderResolver createPlaceholders(BotConfig config, Object platform) {
        ProxyServer proxy = (ProxyServer) platform;
        return new VelocityPlaceholders(proxy, null,
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
