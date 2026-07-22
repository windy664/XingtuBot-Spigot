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

    /**
     * 构造期临时传递 bridge 引用（super() 先于字段赋值，需要跨构造传递）。
     * 调用方在 new BotCommandHandler() 前设置，构造结束后自动清空。
     */
    static volatile VelocityBridge nextBridge;

    private final OpenIdCaptureHandler openIdCapture;
    private final BotConfig config;
    private final VelocityBridge bridge;

    public BotCommandHandler(ProxyServer proxy, org.slf4j.Logger slf4jLogger,
                             BotConfig config, VelocityBridge bridge,
                             TextImageRenderer textImage, Path dataDir) {
        super(config, new VelocityBotLogger(slf4jLogger),
                dataDir != null ? dataDir.toFile() : null, proxy);
        this.config = config;
        this.bridge = bridge;

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
        // bridge 从 static holder 取（super() 构造期间实例字段还未赋值）
        VelocityBridge brg = nextBridge;

        ctx.registerService(ServerQuery.class, new VelocityServerQuery(proxy));

        // 跨服控制台执行（超管 "执行 @子服 命令" 依赖此服务）
        if (brg != null) {
            ctx.registerService(CrossServerConsole.class,
                    (CrossServerConsole) (server, cmd, callback) -> brg.dispatchConsole(server, cmd, callback));
        }

        // 本服控制台执行（Velocity 代理端无游戏控制台，转发到第一个在线子服）
        ctx.registerService(ConsoleExecutor.class,
                (ConsoleExecutor) (cmd, callback) -> {
                    if (brg != null) {
                        // 没指定子服，广播到所有子服（取第一个回复）
                        brg.dispatchConsole("all", cmd, callback);
                    } else {
                        callback.accept("⚠️ 跨服信道不可用");
                    }
                });
    }

    @Override
    protected PlaceholderResolver createPlaceholders(BotConfig config, Object platform) {
        ProxyServer proxy = (ProxyServer) platform;
        // bridge 从 static holder 取
        VelocityBridge brg = nextBridge;
        return new VelocityPlaceholders(proxy, brg);
    }

    public void startCaptureOpenid() { openIdCapture.enableCapture(); }

    public void handle(BotMessageEvent event) {
        super.handle(event);
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
