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
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.util.Pretty;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.bridge.CrossServerProtocol;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.poll.OpenidNameRepository;
import org.windy.xingtubot.common.poll.JsonOpenidNameRepository;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.queue.KnownGroupStore;
import org.windy.xingtubot.common.bridge.CrossServerChannelFactory;
import org.windy.xingtubot.common.bot.QQOnboard;
import org.windy.xingtubot.common.util.Texts;
import org.windy.xingtubot.common.service.SensitiveFilter;
import org.windy.xingtubot.common.runtime.BotRuntimeState;

import java.nio.file.Path;
import java.util.function.Consumer;

@Plugin(
        id = "xingtubotvelocity",
        name = "XingtuBotVelocity",
        version = "2.2.1",
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
    private CrossServerChannelFactory.Holder redisHolder;

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
        OpenidNameCache.getInstance().shutdown();
    }

    private void init() {
        config = new VelocityConfig(dataDir);
        BotRuntimeState.bindDebug(() -> BotRuntimeState.isDebugEnabled());

        // 初始化消息队列持久化
        PendingMessageQueue.getInstance().init(dataDir.toFile());
        // 初始化已知群列表持久化（供「推送到全部群」的主动消息使用）
        KnownGroupStore.getInstance().init(dataDir.toFile());

        // 初始化 OpenID 昵称缓存（L1 内存 + L2 DB）
        initOpenidNameCache();

        VelocityBotLogger botLogger = new VelocityBotLogger(logger);

        adapter = new VelocityAdapter(proxy, () -> BotRuntimeState.isDebugEnabled());

        // 跨服 Bridge 是通用基础设施（PAPI/控制台/群服互联都走它）：只要代理端跑 bot 就建。
        // auth 逻辑完全由 xt-auth 的 AuthVelocityPlugin 处理（DirectAuthAdapter + packetevents）。
        VelocityBridge bridge = null;
        boolean velocityIsBrain = true;
        if (velocityIsBrain) {
            ChannelIdentifier bridgeChannel = MinecraftChannelIdentifier.from(CrossServerProtocol.CHANNEL);
            bridge = new VelocityBridge(proxy, this, bridgeChannel, logger::warn);
            velocityBridge = bridge;
            // 子服注册表（name→address），从 velocity.toml 的 [servers] 段读取，
            // 用于 Redis 广播 SERVER_REGISTRY 让子服自动发现代理名。
            bridge.setServersConfig(readVelocityTomlServers(dataDir));
            // 跨服 Redis 信道（通用基础设施，配置在核心 config）：由核心创建并注入到 bridge。
            redisHolder = CrossServerChannelFactory.create(config, false, botLogger);
            if (redisHolder != null) {
                bridge.setRedisChannel(redisHolder.channel);
            }
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

        // 通过 static holder 把 bridge 传进 BotCommandHandler（super() 构造期间需要）
        BotCommandHandler.nextBridge = bridge;
        commandHandler = new BotCommandHandler(proxy, logger, config, bridge, textImage, dataDir);
        BotCommandHandler.nextBridge = null;
        // 代理端默认为大脑。框架一次性写入宿主，供 xt-auth 等扩展只读。
        commandHandler.getHost().setBrain(velocityIsBrain);
        commandHandler.getHost().registerService("core.allowed-groups",
                (java.util.function.Supplier<java.util.List<String>>) () -> config.getStringList("allowed-groups"));
        // 敏感词过滤（全局单例，供 xt-chatlink / xt-ai 等扩展消费）
        SensitiveFilter sf = SensitiveFilter.fromConfig(config, "sensitive-filter", botLogger);
        commandHandler.getHost().registerService(SensitiveFilter.class, sf);
        // 指定 handler 自动走敏感词：配置 sensitive-filter-handlers: [ai-chat, ...]
        commandHandler.getRegistry().setSensitiveFilter(sf, config.getStringList("sensitive-filter-handlers"));
        proxy.getCommandManager().register("vxtb", new VxtbCommand());
        proxy.getCommandManager().register("qq", new QQCommand(config));

        // 启动字符画 + 配置摘要
        printBanner();
        if (BotRuntimeState.isDebugEnabled()) {
            printDebugInfo();
        }

        // 收到消息后的统一处理（两种模式共用）
        Consumer<BotMessageEvent> listener = event -> {
            adapter.log("[VC] 收到Bot消息: " + event.getMessage());
            commandHandler.handle(event);
        };

        // 如果未配置 app-id，自动进入扫码接入流程
        String gwAppId = config.getString("openapi-app-id", "").trim();
        if (gwAppId.isEmpty()) {
            adapter.log("未配置 openapi-app-id，启动扫码接入流程...");
            QQOnboard onboard =
                    new QQOnboard(new VelocityBotLogger(logger));
            QQOnboard.ScanResult result = onboard.run();
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
            gatewayClient.setOnBotNameResolved(name ->
                    adapter.log("✅ 机器人昵称已自动获取: " + name));
            gatewayClient.start();
            adapter.log("✅ 通信模式 = gateway（QQ 官方 WebSocket 网关）已启动");

            org.windy.xingtubot.common.qq.QqOpenApiClient apiClient = qqBot.getApi();
            if (apiClient != null) {
                commandHandler.getProactiveSender().bind(apiClient);
                if (commandHandler != null && commandHandler.getService() != null) {
                    commandHandler.getService().setApiClient(apiClient);
                }
                adapter.log("✅ 主动消息已启用（模组通知 + 群服互联将即时推送）");
            }
        } else {
            logger.error("[XingtuBot] Gateway 模式配置不全，请检查 openapi-app-id / openapi-client-secret");
        }

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

    /** 反射获取 xt-auth 注册到服务总线的类型，避免编译期依赖 xt-auth。 */
    private Object getServiceByReflect(String className) {
        try {
            if (commandHandler == null || commandHandler.getHost() == null) return null;
            return commandHandler.getHost().getServiceObject(Class.forName(className));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 velocity.toml 的 [servers] 段读取子服注册表（name→address）。
     * 简单解析：找 [servers] 块，逐行读 key = "value"。
     */
    private java.util.Map<String, String> readVelocityTomlServers(Path dataDir) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        // velocity.toml 在代理工作目录（Velocity 启动时的 cwd）
        Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        Path toml = cwd.resolve("velocity.toml");
        if (!java.nio.file.Files.exists(toml)) {
            // 兜底：dataDir 的上两级
            Path abs = dataDir.toAbsolutePath();
            if (abs.getParent() != null && abs.getParent().getParent() != null) {
                toml = abs.getParent().getParent().resolve("velocity.toml");
            }
        }
        if (!java.nio.file.Files.exists(toml)) {
            logger.warn("[跨服] 找不到 velocity.toml, 已尝试: " + cwd.resolve("velocity.toml"));
            return result;
        }
        logger.info("[跨服] 读取 velocity.toml: " + toml);
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(toml, java.nio.charset.StandardCharsets.UTF_8);
            boolean inServers = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    inServers = "[servers]".equals(trimmed);
                    continue;
                }
                if (!inServers || trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // 跳过多行数组（如 try = [...]）
                if (trimmed.startsWith("[")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1).trim();
                // 只处理带引号的字符串值（server 地址），跳过数组/内联表
                if (val.length() < 2 || !val.startsWith("\"") || !val.endsWith("\"")) continue;
                val = val.substring(1, val.length() - 1);
                if (!key.isEmpty() && !val.isEmpty()) {
                    result.put(key, val);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    private void initOpenidNameCache() {
        org.windy.xingtubot.common.poll.OpenidNameRepository repo =
                new org.windy.xingtubot.common.poll.JsonOpenidNameRepository(
                        dataDir.resolve("openid_names.json").toFile(), logger::info);
        OpenidNameCache.getInstance().init(repo);
    }

    /** 启动字符画 + 基本信息（无条件显示）。 */
    private void printBanner() {
        String version = getClass().getPackage().getImplementationVersion();
        for (String line : Texts.banner(version, "Velocity", "/vxtb help")) {
            adapter.log(line);
        }
    }

    /** 详细配置信息（仅 debug 模式）。 */
    private void printDebugInfo() {
        adapter.log("§7[Debug] 角色: 大脑");
        {
            String appId = config.getString("openapi-app-id", "");
            String masked = appId.length() > 4 ? appId.substring(0, 4) + "****" : "未配置";
            adapter.log("§7[Debug] AppID: " + masked);
        }
        adapter.log("§7[Debug] 监听: mention");
        adapter.log("§7[Debug] 跨服: 已启用");
        adapter.log("§7[Debug] 存储: JSON");
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
                    org.windy.xingtubot.common.qq.QqOpenApiClient api = qqBot.getApi();
                    // 异步发送
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            api.sendProactiveGroupMessage(gid, msg);
                            sender.sendMessage(Component.text("✅ 主动消息发送成功！"));
                        } catch (Exception e) {
                            // 主动消息失败 → 回退到被动队列
                            PendingMessageQueue.getInstance()
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
            String connStatus = (gatewayClient != null && gatewayClient.isRunning()) ? "§a已连接" : "§c未连接";

            StringBuilder servers = new StringBuilder();
            for (com.velocitypowered.api.proxy.server.RegisteredServer rs : proxy.getAllServers()) {
                if (servers.length() > 0) servers.append(", ");
                servers.append(rs.getServerInfo().getName())
                        .append("(").append(rs.getPlayersConnected().size()).append(")");
            }

            String boundCount = "—";
            try {
                Object bs = getServiceByReflect("org.windy.xingtubot.common.binding.BindingService");
                if (bs != null) {
                    Object store = bs.getClass().getMethod("getStore").invoke(bs);
                    if (store != null) {
                        @SuppressWarnings("unchecked")
                        java.util.List<?> all = (java.util.List<?>) store.getClass().getMethod("all").invoke(store);
                        boundCount = String.valueOf(all != null ? all.size() : 0);
                    }
                }
            } catch (Exception ignored) {}

            for (String line : Texts.statusBlock("运行状态",
                    "通信模式", "gateway " + connStatus,
                    "在线人数", proxy.getPlayerCount() + " 人",
                    "子服", servers.toString(),
                    "已绑定", boundCount + " 人")) {
                sender.sendMessage(Component.text(line));
            }
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
