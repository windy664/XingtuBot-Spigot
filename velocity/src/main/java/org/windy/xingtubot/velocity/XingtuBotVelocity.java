package org.windy.xingtubot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.util.Pretty;
import org.windy.xingtubot.common.binding.BindingStorageFactory;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;

import java.nio.file.Path;
import java.util.function.Consumer;

@Plugin(
        id = "xingtubotvelocity",
        name = "XingtuBotVelocity",
        version = "1.4",
        authors = {"风吟"},
        dependencies = {
                @com.velocitypowered.api.plugin.Dependency(id = "packetevents", optional = true)
        }
)
public class XingtuBotVelocity implements org.windy.xingtubot.common.module.XingtuBotHostProvider {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private VelocityConfig config;
    private QqBot qqBot;
    private QQGatewayClient gatewayClient;
    private VelocityAdapter adapter;
    private BotCommandHandler commandHandler;
    private VelocityBridge velocityBridge;
    private org.windy.xingtubot.common.bridge.CrossServerChannelFactory.Holder redisHolder;

    @Inject
    public XingtuBotVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        init();
    }

    @Subscribe
    public void onProxyShutdown(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) {
        if (commandHandler != null) {
            commandHandler.shutdown();
        }
        if (redisHolder != null) {
            redisHolder.close();
        }
        org.windy.xingtubot.common.poll.OpenidNameCache.getInstance().shutdown();
    }

    private void init() {
        config = new VelocityConfig(dataDir);

        // 初始化消息队列持久化
        org.windy.xingtubot.common.queue.PendingMessageQueue.getInstance().init(dataDir.toFile());
        // 初始化已知群列表持久化（供「推送到全部群」的主动消息使用）
        org.windy.xingtubot.common.queue.KnownGroupStore.getInstance().init(dataDir.toFile());

        // 初始化 OpenID 昵称缓存（L1 内存 + L2 DB）
        initOpenidNameCache();

        VelocityBotLogger botLogger = new VelocityBotLogger(logger);

        adapter = new VelocityAdapter(proxy, () -> config.getBoolean("debug", false));

        // 跨服 Bridge 是通用基础设施（PAPI/控制台/群服互联/白名单都走它）：只要代理端跑 bot 就建，
        // 不与白名单开关耦合（否则关白名单会误伤跨服 PAPI/控制台）。BindingService 由 xt-auth 注册。
        VelocityBridge bridge = null;
        boolean velocityIsBrain = BotLauncher.resolveMode(config) != BotLauncher.Mode.OFF;
        if (velocityIsBrain) {
            String appId = config.getString("openapi-app-id", "");
            if (appId.isEmpty()) {
                logger.warn("[XingtuBot] 未配置 openapi-app-id，白名单头像比对将无法工作");
            }
            ChannelIdentifier bridgeChannel = MinecraftChannelIdentifier.from(CrossServerProtocol.CHANNEL);
            PluginMessageAuthAdapter authAdapter = new PluginMessageAuthAdapter(proxy, bridgeChannel);
            // BindingService 由 xt-auth 注册为 service，bridge 惰性获取
            bridge = new VelocityBridge(proxy, this, bridgeChannel, authAdapter, logger::warn,
                    () -> commandHandler != null && commandHandler.getHost() != null
                            ? commandHandler.getHost().getService(org.windy.xingtubot.common.binding.BindingService.class) : null);
            velocityBridge = bridge;
            // 跨服 Redis 信道（通用基础设施，配置在核心 config）：由核心创建并注入到 bridge。
            redisHolder = org.windy.xingtubot.common.bridge.CrossServerChannelFactory.create(config, false, botLogger);
            if (redisHolder != null) {
                bridge.setRedisChannel(redisHolder.channel);
            }
            // 未绑定进服的加群二维码由 xt-auth 附属插件经 setOnUnboundJoin 注册（白名单功能完整归属 xt-auth）。
            adapter.log("✅ 白名单大脑已就绪（Velocity 主导，子服执行 AuthMe）");
        }

        // 群服互联由 xt-chatlink 扩展插件处理（GroupChatLink 创建 + 配置）

        // 文字生图
        java.io.File fontFile = dataDir.resolve("font.ttf").toFile();
        java.io.File templateDir = dataDir.resolve("templates").toFile();
        templateDir.mkdirs();
        org.windy.xingtubot.common.image.TextImageRenderer textImage =
                new org.windy.xingtubot.common.image.TextImageRenderer(fontFile, templateDir);
        logger.info("[XingtuBot] 生图字体：" + textImage.getFontSource());

        // 自定义问答/自定义命令、翻译、模组工具均已迁出为各功能模块（module-custom/translate/modtools），
        // 由 BotCommandHandler 经 ModuleLoader 装配（占位符/绑定库/跨服执行经能力服务注入）。

        commandHandler = new BotCommandHandler(proxy, logger, config, bridge, textImage, dataDir);
        // 代理端：跑 bot（server-role 非 off）即为大脑。框架一次性写入宿主，供 xt-auth 等扩展只读。
        commandHandler.getHost().setBrain(velocityIsBrain);
        proxy.getCommandManager().register("vxtb", new VxtbCommand());
        proxy.getCommandManager().register("qq", new QQCommand(config));

        // 收到消息后的统一处理（两种模式共用）
        Consumer<BotMessageEvent> listener = event -> {
            adapter.log("[VC] 收到Bot消息: " + event.getMessage());
            commandHandler.handle(event);
        };

        switch (BotLauncher.resolveMode(config)) {
            case OFF:
                adapter.log("通信模式 = off，机器人通信未启用。");
                return;
            case GATEWAY:
                // 如果未配置 app-id，自动进入扫码接入流程
                String gwAppId = config.getString("openapi-app-id", "").trim();
                if (gwAppId.isEmpty()) {
                    adapter.log("未配置 openapi-app-id，启动扫码接入流程...");
                    org.windy.xingtubot.common.bot.QQOnboard onboard =
                            new org.windy.xingtubot.common.bot.QQOnboard(new VelocityBotLogger(logger));
                    org.windy.xingtubot.common.bot.QQOnboard.ScanResult result = onboard.run();
                    if (result != null) {
                        config.set("openapi-app-id", result.appId);
                        config.set("openapi-client-secret", result.clientSecret);
                        try {
                            config.save();
                            adapter.log("✅ 凭据已写入 config.yml");
                        } catch (Exception e) {
                            logger.warn("[XingtuBot] 写入 config.yml 失败: " + e.getMessage());
                        }
                    } else {
                        logger.error("[XingtuBot] 扫码接入失败或超时，请手动填写 openapi-app-id 和 openapi-client-secret");
                        return;
                    }
                }
                BotLauncher.GatewayResult gw = BotLauncher.buildGateway(config, adapter,
                        new VelocityBotLogger(logger), listener);
                if (gw != null) {
                    qqBot = gw.bot;
                    gatewayClient = gw.gatewayClient;
                    // 机器人昵称由 QQ API 自动写入 BotIdentity（QQGatewayClient 内部已处理）；此处仅记日志
                    gatewayClient.setOnBotNameResolved(name ->
                            adapter.log("✅ 机器人昵称已自动获取: " + name));
                    gatewayClient.start();
                    adapter.log("✅ 通信模式 = gateway（QQ 官方 WebSocket 网关）已启动");

                    // 接线主动消息
                    org.windy.xingtubot.common.api.QqOpenApiClient apiClient = qqBot.getApi();
                    if (apiClient != null) {
                        // 模组更新等主动推送：填实惰性句柄（module-modtools 持有同一句柄）。
                        // 群服互联（GroupChatLink）也用同一 ProactiveSender holder——由 xt-chatlink 自己拉取，
                        // 故此处 bind 一次即可让两边同时就绪，不再 push 裸 apiClient 给尚未注册的 GroupChatLink。
                        commandHandler.getProactiveSender().bind(apiClient);
                        // 对外 API 发消息也走主动优先
                        if (commandHandler != null && commandHandler.getService() != null) {
                            commandHandler.getService().setApiClient(apiClient);
                        }
                        adapter.log("✅ 主动消息已启用（模组通知 + 群服互联将即时推送）");
                    }
                } else {
                    logger.error("[XingtuBot] Gateway 模式配置不全，请检查 openapi-app-id / openapi-client-secret");
                }
                return;
            default:
                logger.error("[XingtuBot] 未知的 server-role，请检查配置。");
        }

        // 启动配置摘要
        printConfigSummary();
    }

    /**
     * 对外 API（供第三方 Velocity 插件扩展群命令）。
     * 第三方用法：proxy.getPluginManager().getPlugin("xingtubotvelocity")
     *           .flatMap(PluginContainer::getInstance).map(p -> ((XingtuBotVelocity) p).getService())
     */
    public org.windy.xingtubot.common.api.XingtuBotService getService() {
        return commandHandler != null ? commandHandler.getService() : null;
    }

    /**
     * 宿主能力面（供 Velocity 端附属扩展插件接入）。
     * 扩展用法：proxy.getPluginManager().getPlugin("xingtubotvelocity")
     *         .flatMap(PluginContainer::getInstance).map(p -> ((XingtuBotVelocity) p).getHost())
     */
    public org.windy.xingtubot.common.module.XingtuBotHost getHost() {
        return commandHandler != null ? commandHandler.getHost() : null;
    }

    /** VelocityBridge（供 xt-auth 注入 Redis 信道）。 */
    public VelocityBridge getVelocityBridge() {
        return velocityBridge;
    }

    private void initOpenidNameCache() {
        String storageType = config.getString("storage-type", "json").trim().toLowerCase();
        org.windy.xingtubot.common.poll.OpenidNameRepository repo;

        switch (storageType) {
            case "mysql":
                repo = org.windy.xingtubot.common.poll.JdbcOpenidNameRepository.mysql(
                        config.getString("mysql-host", "127.0.0.1"),
                        config.getInt("mysql-port", 3306),
                        config.getString("mysql-database", "xingtubot"),
                        config.getString("mysql-user", "root"),
                        config.getString("mysql-password", ""),
                        logger::info);
                break;
            case "sqlite":
            default:
                repo = org.windy.xingtubot.common.poll.JdbcOpenidNameRepository.sqlite(
                        dataDir.resolve("openid_names.db").toFile().getAbsolutePath(),
                        logger::info);
                break;
        }

        org.windy.xingtubot.common.poll.OpenidNameCache.getInstance().init(repo);
    }

    private void printConfigSummary() {
        boolean botOn = BotLauncher.resolveMode(config) != BotLauncher.Mode.OFF;
        String role = config.getString("server-role", "auto");
        adapter.log("");
        adapter.log("─────────────  昕途机器人 · 启动摘要  ─────────────");

        // ── 通信 ──
        section("📡 通信");
        kv("角色", role + (botOn ? "（大脑）" : "（off · 不跑 bot）"));
        if (botOn) {
            String appId = config.getString("openapi-app-id", "");
            String masked = appId.length() > 4 ? appId.substring(0, 4) + "****" : "(未配置)";
            kv("AppID", masked);
            java.util.List<String> groups = config.getStringList("allowed-groups");
            kv("群白名单", groups.isEmpty() || groups.contains("*") ? "全部群" : groups.toString());
        }
        kv("监听模式", config.getString("listen-mode", "mention"));
        kv("调试模式", onOff(config.getBoolean("debug", false)));

        // ── 部署（白名单由 XingtuBot-Auth 附属提供，状态见其自身日志/config）──
        section("🖥 部署");
        boolean runsBridge = BotLauncher.resolveMode(config) != BotLauncher.Mode.OFF;
        kv("跨服", runsBridge ? "Velocity 大脑（已建 Bridge：PAPI/控制台/群服互联/白名单）" : "未启用（server-role=off）");
        kv("存储", config.getString("storage-type", "json"));

        // ── 功能扩展（群服互联/模组/AI/迎送/娱乐等均由附属插件提供，各自独立 config）──
        section("🧩 功能扩展");
        String[][] exts = {
                {"xingtubot-auth", "白名单+登录"},
                {"xingtubot-chatlink", "群服互联"},
                {"xingtubot-group", "迎送+自定义"},
                {"xingtubot-fun", "娱乐"},
                {"xingtubot-modquery", "模组工具"},
                {"xingtubot-ai", "AI 对话"},
                {"xingtubot-github", "项目追踪"},
        };
        for (String[] ext : exts) {
            boolean installed = proxy.getPluginManager().getPlugin(ext[0]).isPresent();
            kv(ext[1], installed ? "已装 (" + ext[0] + ")" : "未安装");
        }

        adapter.log("──────────────────────────────────────────────────");
    }

    /** 分组标题。 */
    private void section(String title) {
        adapter.log("  " + title);
    }

    /** 「键值对」行：键按显示宽度对齐到 14 格，再接值。 */
    private void kv(String label, String value) {
        adapter.log("     " + Pretty.padEnd(label, 14) + value);
    }

    private static String onOff(boolean on) {
        return on ? "开" : "关";
    }

    // ==================== /vxtb 命令 ====================

    private class VxtbCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            if (args.length == 0) {
                sender.sendMessage(Component.text("用法: /vxtb <connect|disconnect|status|captureid|reply>"));
                return;
            }

            switch (args[0].toLowerCase()) {
                case "reply": {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("用法: /vxtb reply <内容>"));
                        break;
                    }
                    GroupChatLink gclReply = commandHandler != null && commandHandler.getHost() != null
                            ? commandHandler.getHost().getService(GroupChatLink.class) : null;
                    if (gclReply == null) {
                        sender.sendMessage(Component.text("❌ 群服互联未启用"));
                        break;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
                    boolean ok = gclReply.replyToLastGroup(sb.toString().trim());
                    sender.sendMessage(Component.text(ok ? "✅ 已回复到群"
                            : "⚠️ 发送失败（无可用通信通道）"));
                    break;
                }
                case "connect":
                    handleConnect(sender);
                    break;
                case "disconnect":
                    handleDisconnect(sender);
                    break;
                case "status":
                    handleStatus(sender);
                    break;
                case "captureid":
                    if (commandHandler == null) {
                        sender.sendMessage(Component.text("❌ 机器人未就绪"));
                    } else {
                        commandHandler.startCaptureOpenid();
                        sender.sendMessage(Component.text(
                                "✅ 已开启 openid 捕获。请让目标用户在群里 @机器人 发任意一句话，"
                                + "其 openid 将打印到本控制台（一次性）。"));
                    }
                    break;
                case "proactive": {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("用法: /vxtb proactive <群openid> [消息内容]"));
                        sender.sendMessage(Component.text("测试主动消息推送（不依赖被动回复窗口）"));
                        break;
                    }
                    String gid = args[1];
                    String content = args.length > 2
                            ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                            : "这是一条主动消息测试";
                    if (qqBot == null || qqBot.getApi() == null) {
                        sender.sendMessage(Component.text("❌ 机器人未就绪（API 不可用）"));
                        break;
                    }
                    final String msg = "🔔 [主动消息] " + content;
                    sender.sendMessage(Component.text("正在发送主动消息到群 " + gid + "..."));
                    org.windy.xingtubot.common.api.QqOpenApiClient api = qqBot.getApi();
                    // 异步发送
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            api.sendProactiveGroupMessage(gid, msg);
                            sender.sendMessage(Component.text("✅ 主动消息发送成功！"));
                        } catch (Exception e) {
                            // 主动消息失败 → 回退到被动队列
                            org.windy.xingtubot.common.queue.PendingMessageQueue.getInstance()
                                    .offer(gid, msg);
                            sender.sendMessage(Component.text("⚠️ 主动消息失败（无权限），已回退到被动队列"));
                            sender.sendMessage(Component.text("下次群里有人 @机器人 时会一起发出"));
                        }
                    });
                    break;
                }
                default:
                    sender.sendMessage(Component.text("未知子命令: " + args[0]));
            }
        }

        private void handleConnect(CommandSource sender) {
            if (gatewayClient != null) {
                if (gatewayClient.isRunning()) {
                    sender.sendMessage(Component.text("✅ Gateway 当前已连接。"));
                } else {
                    gatewayClient.start();
                    sender.sendMessage(Component.text("正在尝试连接 Gateway..."));
                }
            } else {
                sender.sendMessage(Component.text("❌ 机器人通信未初始化。"));
            }
        }

        private void handleDisconnect(CommandSource sender) {
            if (gatewayClient != null) {
                gatewayClient.stop();
                sender.sendMessage(Component.text("🧹 已断开 Gateway 连接。"));
            } else {
                sender.sendMessage(Component.text("❌ 机器人通信未初始化。"));
            }
        }

        private void handleStatus(CommandSource sender) {
            sender.sendMessage(Component.text("╔══════════════════════════╗"));
            sender.sendMessage(Component.text("║   §6昕途机器人运行状态   §r║"));
            sender.sendMessage(Component.text("╠══════════════════════════╣"));

            // 通信状态
            BotLauncher.Mode mode = BotLauncher.resolveMode(config);
            boolean botOff = mode == BotLauncher.Mode.OFF;
            String connStatus;
            if (botOff) {
                connStatus = "§c已关闭（server-role=off）";
            } else {
                connStatus = (gatewayClient != null && gatewayClient.isRunning()) ? "§a已连接" : "§c未连接";
            }
            sender.sendMessage(Component.text("║ §7通信: §f" + mode + " " + connStatus));

            // 在线人数
            sender.sendMessage(Component.text("║ §7在线: §f" + proxy.getPlayerCount() + " 人"));

            // 子服
            StringBuilder servers = new StringBuilder();
            for (com.velocitypowered.api.proxy.server.RegisteredServer rs : proxy.getAllServers()) {
                if (servers.length() > 0) servers.append(", ");
                servers.append(rs.getServerInfo().getName())
                        .append("(").append(rs.getPlayersConnected().size()).append(")");
            }
            sender.sendMessage(Component.text("║ §7子服: §f" + servers));

            // 绑定数据（由 xt-auth 注册为 service）
            org.windy.xingtubot.common.binding.BindingService bs =
                    commandHandler != null && commandHandler.getHost() != null
                    ? commandHandler.getHost().getService(org.windy.xingtubot.common.binding.BindingService.class) : null;
            if (bs != null) {
                try {
                    int count = bs.getStore().all().size();
                    sender.sendMessage(Component.text("║ §7已绑定: §f" + count + " 人"));
                } catch (Exception ignored) {
                }
            }

            sender.sendMessage(Component.text("╚══════════════════════════╝"));
        }
    }

    // ==================== /qq 命令 ====================

    private class QQCommand implements SimpleCommand {
        private final String permission;

        QQCommand(BotConfig config) {
            this.permission = "xingtubot.qq.send";
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();

            if (args.length == 0) {
                sender.sendMessage(Component.text("用法: /qq <消息内容>"));
                return;
            }

            if (!sender.hasPermission(permission)) {
                sender.sendMessage(Component.text("❌ 你没有权限使用此命令（需要 " + permission + "）"));
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(args[i]);
            }
            String content = sb.toString().trim();
            if (content.isEmpty()) {
                sender.sendMessage(Component.text("❌ 消息内容不能为空"));
                return;
            }

            String senderName = "控制台";
            if (sender instanceof Player) {
                senderName = ((Player) sender).getUsername();
            }

            String message = "📢 [" + senderName + "] " + content;

            GroupChatLink gcl = commandHandler != null && commandHandler.getHost() != null
                    ? commandHandler.getHost().getService(GroupChatLink.class) : null;
            if (gcl != null && gcl.replyToLastGroup(message)) {
                sender.sendMessage(Component.text("✅ 已发送到 QQ 群: " + content));
            } else {
                sender.sendMessage(Component.text("⚠️ 发送失败（无可用通信通道）"));
            }
        }
    }
}
