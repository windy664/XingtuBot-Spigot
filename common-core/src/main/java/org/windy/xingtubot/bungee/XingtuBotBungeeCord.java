package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.util.Pretty;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * BungeeCord 端主类。与 XingtuBotVelocity 功能对等。
 */
public class XingtuBotBungeeCord extends Plugin implements Listener, XingtuBotHostProvider {

    private BungeeCordConfig config;
    private QqBot qqBot;
    private QQGatewayClient gatewayClient;
    private BungeeCordAdapter adapter;
    private BungeeCordCommandHandler commandHandler;
    private BungeeCordBridge bridge;
    private org.windy.xingtubot.common.bridge.CrossServerChannelFactory.Holder redisHolder;

    @Override
    public void onEnable() {
        init();
        getProxy().getPluginManager().registerListener(this, this);
    }

    @Override
    public void onDisable() {
        if (commandHandler != null) commandHandler.shutdown();
        if (redisHolder != null) redisHolder.close();
        OpenidNameCache.getInstance().shutdown();
    }

    @Override
    public org.windy.xingtubot.common.module.XingtuBotHost getHost() {
        return commandHandler != null ? commandHandler.getHost() : null;
    }

    /** 核心 BungeeCordBridge（供 xt-auth 附属注册 onUnboundJoin 等回调；非白名单大脑时为 null）。 */
    public BungeeCordBridge getBridge() {
        return bridge;
    }

    private void init() {
        config = new BungeeCordConfig(getDataFolder().toPath());

        PendingMessageQueue.getInstance().init(getDataFolder());
        // 初始化已知群列表持久化（供「推送到全部群」的主动消息使用）
        org.windy.xingtubot.common.queue.KnownGroupStore.getInstance().init(getDataFolder());
        initOpenidNameCache();

        adapter = new BungeeCordAdapter(getProxy(), () -> config.getBoolean("debug", false));

        // 跨服 Bridge 是通用基础设施（PAPI/控制台/群服互联都走它）：只要代理端跑 bot 就建。
        // auth 逻辑完全由 xt-auth 的 AuthBungeeCordPlugin 处理（DirectAuthAdapter + packetevents）。
        bridge = null;
        boolean velocityIsBrain = BotLauncher.resolveMode(config) != BotLauncher.Mode.OFF;
        if (velocityIsBrain) {
            bridge = new BungeeCordBridge(getProxy(), this,
                    org.windy.xingtubot.common.bridge.CrossServerProtocol.CHANNEL,
                    getLogger()::info);
            // 跨服 Redis 信道（通用基础设施，配置在核心 config）：由核心创建并注入到 bridge。
            redisHolder = org.windy.xingtubot.common.bridge.CrossServerChannelFactory.create(
                    config, false, new BungeeCordBotLogger(getLogger()));
            if (redisHolder != null) {
                bridge.setRedisChannel(redisHolder.channel);
            }
        }

        // 群服互联由 xt-chatlink 扩展插件处理

        // 文字生图
        File fontFile = new File(getDataFolder(), "font.ttf");
        File templateDir = new File(getDataFolder(), "templates");
        templateDir.mkdirs();
        org.windy.xingtubot.common.image.TextImageRenderer textImage =
                new org.windy.xingtubot.common.image.TextImageRenderer(fontFile, templateDir);
        getLogger().info("[XingtuBot] 生图字体：" + textImage.getFontSource());

        commandHandler = new BungeeCordCommandHandler(getProxy(), getLogger(), config, bridge, textImage, getDataFolder());
        // 代理端：跑 bot（server-role 非 off）即为大脑。框架一次性写入宿主，供 xt-auth 等扩展只读。
        commandHandler.getHost().setBrain(velocityIsBrain);
        registerCommands();

        // 收到消息后的统一处理
        Consumer<BotMessageEvent> listener = event -> {
            getLogger().info("[BC] 收到Bot消息: " + event.getMessage());
            commandHandler.handle(event);
        };

        switch (BotLauncher.resolveMode(config)) {
            case OFF:
                adapter.log("通信模式 = off，机器人通信未启用。");
                return;
            case GATEWAY:
                String gwAppId = config.getString("openapi-app-id", "").trim();
                if (gwAppId.isEmpty()) {
                    adapter.log("未配置 openapi-app-id，启动扫码接入流程...");
                    org.windy.xingtubot.common.bot.QQOnboard onboard =
                            new org.windy.xingtubot.common.bot.QQOnboard(new BungeeCordBotLogger(getLogger()));
                    org.windy.xingtubot.common.bot.QQOnboard.ScanResult result = onboard.run();
                    if (result != null) {
                        config.set("openapi-app-id", result.appId);
                        config.set("openapi-client-secret", result.clientSecret);
                        try {
                            config.save();
                            adapter.log("✅ 凭据已写入 config.yml");
                        } catch (Exception e) {
                            getLogger().warning("[XingtuBot] 写入 config.yml 失败: " + e.getMessage());
                        }
                    } else {
                        getLogger().severe("[XingtuBot] 扫码接入失败或超时，请手动填写 openapi-app-id 和 openapi-client-secret");
                        return;
                    }
                }
                BotLauncher.GatewayResult gw = BotLauncher.buildGateway(config, adapter,
                        new BungeeCordBotLogger(getLogger()), listener);
                if (gw != null) {
                    qqBot = gw.bot;
                    gatewayClient = gw.gatewayClient;
                    // 机器人昵称由 QQ API 自动写入 BotRuntimeState（QQGatewayClient 内部已处理）；此处仅记日志
                    gatewayClient.setOnBotNameResolved(name ->
                            adapter.log("✅ 机器人昵称已自动获取: " + name));
                    gatewayClient.start();
                    adapter.log("✅ 通信模式 = gateway（QQ 官方 WebSocket 网关）已启动");

                    // 接线主动消息
                    org.windy.xingtubot.common.qq.QqOpenApiClient apiClient = qqBot.getApi();
                    if (apiClient != null) {
                        // 群服互联（BungeeCordGroupChatLink）与模组通知共用同一 ProactiveSender holder，
                        // 由 xt-chatlink 自己拉取；故 bind 一次即同时就绪，不再 push 给尚未注册的 GroupChatLink。
                        commandHandler.getProactiveSender().bind(apiClient);
                        if (commandHandler.getService() != null) {
                            commandHandler.getService().setApiClient(apiClient);
                        }
                        adapter.log("✅ 主动消息已启用");
                    }
                } else {
                    getLogger().severe("[XingtuBot] Gateway 模式配置不全");
                }
                return;
            default:
                getLogger().severe("[XingtuBot] 未知的 server-role");
        }

        printBanner();
        if (config.getBoolean("debug", false)) {
            printDebugInfo();
        }
    }

    private void registerCommands() {
        // /bxtb 命令
        getProxy().getPluginManager().registerCommand(this, new Command("bxtb", "xingtubot.admin") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (args.length == 0) {
                    sender.sendMessage(new TextComponent("§e用法: /bxtb <reload|reply|status|captureid|proactive>"));
                    return;
                }
                switch (args[0].toLowerCase()) {
                    case "reply":
                        if (args.length < 2) { sender.sendMessage(new TextComponent("§c用法: /bxtb reply <内容>")); return; }
                        BungeeCordGroupChatLink gcl = commandHandler.getHost() != null
                                ? commandHandler.getHost().getService(BungeeCordGroupChatLink.class) : null;
                        if (gcl == null) { sender.sendMessage(new TextComponent("❌ 群服互联未启用")); return; }
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
                        boolean ok = gcl.replyToLastGroup(sb.toString().trim());
                        sender.sendMessage(new TextComponent(ok ? "✅ 已回复到群" : "⚠️ 发送失败"));
                        break;
                    case "captureid":
                        if (commandHandler == null) { sender.sendMessage(new TextComponent("❌ 机器人未就绪")); return; }
                        commandHandler.startCaptureOpenid();
                        sender.sendMessage(new TextComponent("✅ 已开启 openid 捕获"));
                        break;
                    case "status":
                        sender.sendMessage(new TextComponent("§eBungeeCord 模式运行中"));
                        sender.sendMessage(new TextComponent("§7在线: §f" + getProxy().getOnlineCount() + " 人"));
                        break;
                    default:
                        sender.sendMessage(new TextComponent("§c未知子命令: " + args[0]));
                }
            }
        });

        // /bqq 命令
        getProxy().getPluginManager().registerCommand(this, new Command("bqq", "xingtubot.qq.send") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (args.length == 0) {
                    sender.sendMessage(new TextComponent("§e用法: /bqq <消息内容>"));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(" ");
                    sb.append(args[i]);
                }
                String content = sb.toString().trim();
                if (content.isEmpty()) { sender.sendMessage(new TextComponent("❌ 消息内容不能为空")); return; }

                String senderName = sender instanceof ProxiedPlayer ? ((ProxiedPlayer) sender).getName() : "控制台";
                String message = "📢 [" + senderName + "] " + content;

                BungeeCordGroupChatLink gcl = commandHandler.getHost() != null
                        ? commandHandler.getHost().getService(BungeeCordGroupChatLink.class) : null;
                if (gcl != null && gcl.replyToLastGroup(message)) {
                    sender.sendMessage(new TextComponent("✅ 已发送到 QQ 群: " + content));
                } else {
                    sender.sendMessage(new TextComponent("⚠️ 发送失败（无可用通信通道）"));
                }
            }
        });
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        // 玩家进服
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        OpenidNameCache.getInstance().shutdown();
    }

    private void initOpenidNameCache() {
        org.windy.xingtubot.common.poll.OpenidNameRepository repo =
                new org.windy.xingtubot.common.poll.JsonOpenidNameRepository(
                        new File(getDataFolder(), "openid_names.json"), getLogger()::info);
        OpenidNameCache.getInstance().init(repo);
    }

    private void printBanner() {
        getLogger().info("");
        getLogger().info(" ██╗  ██╗██╗███╗   ██╗ ██████╗ ████████╗██╗   ██╗██████╗  ██████╗ ████████╗");
        getLogger().info(" ╚██╗██╔╝██║████╗  ██║██╔════╝ ╚══██╔══╝██║   ██║██╔══██╗██╔═══██╗╚══██╔══╝");
        getLogger().info("  ╚███╔╝ ██║██╔██╗ ██║██║        ██║   ██║   ██║██████╔╝██║   ██║   ██║   ");
        getLogger().info("  ██╔██╗ ██║██║╚██╗██║██║        ██║   ██║   ██║██╔══██╗██║   ██║   ██║   ");
        getLogger().info(" ██╔╝ ██╗██║██║ ╚████║╚██████╗   ██║   ╚██████╔╝██████╔╝╚██████╔╝   ██║   ");
        getLogger().info(" ╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝ ╚═════╝   ╚═╝    ╚═════╝ ╚═════╝  ╚═════╝    ╚═╝   ");
        getLogger().info("");
        getLogger().info("▌ 昕途机器人 · BungeeCord");
        getLogger().info("▌ ✔ 已启动  输入 /bqq help 查看命令");
        getLogger().info("");
    }

    private void printDebugInfo() {
        boolean botOn = BotLauncher.resolveMode(config) != BotLauncher.Mode.OFF;
        getLogger().info("[Debug] 角色: " + config.getString("server-role", "auto") + (botOn ? "（大脑）" : "（off）"));
        getLogger().info("[Debug] 监听: " + config.getString("listen-mode", "mention"));
        getLogger().info("[Debug] 跨服: " + (botOn ? "已启用" : "未启用"));
    }
}
