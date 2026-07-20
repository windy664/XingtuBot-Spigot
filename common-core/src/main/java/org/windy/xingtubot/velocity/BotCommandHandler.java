package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessage;
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

        openIdCapture = new OpenIdCaptureHandler();
        openIdCapture.setConsoleLogger(m -> proxy.getConsoleCommandSource().sendMessage(Component.text(m)));
        getRegistry().register(openIdCapture);

        getHost().registerService("xingtubot.chatlink.gameBridge",
                (java.util.function.BiConsumer<org.windy.xingtubot.common.event.BotMessageContext, String>) (event, content) -> {
            GroupChatLink gcl = getHost().getService(GroupChatLink.class);
            if (gcl != null) {
                String withImg = appendImageUrls(content, event.getImageUrls());
                gcl.onGroupMessage(event, senderNameOf(event), withImg);
            }
        });
    }

    @Override
    protected void registerPlatformServices(ModuleContextImpl ctx, BotConfig config,
                                             BotLogger logger, File dataFolder, Object platform) {
        ProxyServer proxy = (ProxyServer) platform;

        ctx.registerService(ServerQuery.class, new VelocityServerQuery(proxy));

    }

    @Override
    protected PlaceholderResolver createPlaceholders(BotConfig config, Object platform) {
        ProxyServer proxy = (ProxyServer) platform;
        return new VelocityPlaceholders(proxy, null);
    }

    public void startCaptureOpenid() { openIdCapture.enableCapture(); }

    public void handle(BotMessageEvent event) {
        handle(event, getPermission().isAdmin(event.getSenderId()));
    }

    private static String appendImageUrls(String content, java.util.List<String> urls) {
        String base = content == null ? "" : content;
        if (urls == null || urls.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base);
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(url.trim());
        }
        return sb.toString();
    }
}
