package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.qq.QqOpenApiClient;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.bot.QQOnboard;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.poll.JsonOpenidNameRepository;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.poll.OpenidNameRepository;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.util.Pretty;
import org.windy.xingtubot.common.util.Texts;
import org.windy.xingtubot.common.bridge.CrossServerChannelFactory;
import org.windy.xingtubot.common.bridge.CrossServerChannel;
import org.windy.xingtubot.common.queue.KnownGroupStore;
import org.windy.xingtubot.bukkit.util.ProxyDetector;
import org.windy.xingtubot.common.service.SensitiveFilter;
import org.windy.xingtubot.common.runtime.BotRuntimeState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class XingtuBot extends JavaPlugin implements Listener {

    private QqBot qqBot;
    private QQGatewayClient gatewayClient;
    private GuildMessageEvent lastEvent;
    private SpigotCommandHandler spigotCommandHandler;
    private QQSendCommand qqSendCommand;
    private CrossServerChannelFactory.Holder redisHolder;
    private static XingtuBot instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        BotRuntimeState.bindDebug(() -> BotRuntimeState.isDebugEnabled());
        printBanner();

        // 初始化消息队列持久化
        PendingMessageQueue.getInstance().init(getDataFolder());
        // 初始化已知群列表持久化（供「推送到全部群」的主动消息使用）
        KnownGroupStore.getInstance().init(getDataFolder());

        // 初始化 OpenID 昵称缓存（L1 内存 + L2 DB）
        initOpenidNameCache();

        FileConfiguration config = getConfig();
        boolean slave = resolveSlave(config);
        if (slave) {
            getLogger().info("部署：检测到代理(手脚模式 slave)，本机 bot 已禁用，由代理大脑统一接管跨服。");
        } else {
            startBot(config);
        }
        registerCommands();
        enableModules(config, slave);

        // 跨服 Redis 信道（通用基础设施，配置在核心 config）：核心创建并注册到服务总线，
        redisHolder = CrossServerChannelFactory.create(
                new SpigotConfig(config), true, new SpigotBotLogger(getLogger()));
        if (redisHolder != null && spigotCommandHandler != null) {
            spigotCommandHandler.getHost().registerService(
                    CrossServerChannel.class, redisHolder.channel);
            // 核心处理跨服控制台命令（DO_CONSOLE）—— 不依赖 xt-auth
            redisHolder.channel.addMessageHandler((fromServer, data) -> {
                org.windy.xingtubot.common.bridge.BridgeCodec.Decoded msg =
                        org.windy.xingtubot.common.bridge.BridgeCodec.decode(data);
                if (msg == null) return;
                switch (msg.type) {
                    case DO_CONSOLE: {
                        String target = msg.field(0);
                        String requestId = msg.field(1);
                        String command = msg.field(2);
                        if (target == null || command == null) break;
                        String myName = resolveServerName();
                        if (!target.equals(myName) && !"all".equalsIgnoreCase(target)) break;
                        getLogger().info("[跨服] 收到远程命令: " + command + " (from " + fromServer + ")");
                        org.windy.xingtubot.bukkit.module.console.ConsoleExecutor.execute(
                                this, command, output ->
                                    sendBridgeResponse(org.windy.xingtubot.common.bridge.BridgeCodec.encode(
                                            org.windy.xingtubot.common.bridge.CrossServerProtocol.Type.CONSOLE_RESULT,
                                            requestId, myName, output)));
                        break;
                    }
                    case WHO_IS_BOSS: {
                        // 握手：代理端回应 I_AM_BOSS + 注册名
                        String proxyName = org.windy.xingtubot.common.runtime.BotRuntimeState.getProxyServerName();
                        sendBridgeResponse(org.windy.xingtubot.common.bridge.BridgeCodec.encode(
                                org.windy.xingtubot.common.bridge.CrossServerProtocol.Type.I_AM_BOSS,
                                proxyName != null ? proxyName : ""));
                        break;
                    }
                    case I_AM_BOSS: {
                        String proxyName = msg.field(0);
                        if (proxyName != null && !proxyName.isEmpty()) {
                            org.windy.xingtubot.common.runtime.BotRuntimeState.setProxyServerName(proxyName);
                            getLogger().info("✅ 代理端注册名: " + proxyName);
                        }
                        break;
                    }
                    case SERVER_REGISTRY: {
                        // 代理端广播的子服注册表：按端口匹配自动发现本服的代理名
                        String registry = msg.field(0);
                        if (registry == null || registry.isEmpty()) break;
                        int myPort = Bukkit.getPort();
                        for (String entry : registry.split(",")) {
                            int eq = entry.indexOf('=');
                            if (eq < 0) continue;
                            String name = entry.substring(0, eq).trim();
                            String addr = entry.substring(eq + 1).trim();
                            int lastColon = addr.lastIndexOf(':');
                            if (lastColon < 0) continue;
                            try {
                                int port = Integer.parseInt(addr.substring(lastColon + 1).trim());
                                if (port == myPort) {
                                    org.windy.xingtubot.common.runtime.BotRuntimeState.setProxyServerName(name);
                                    getLogger().info("✅ [自动发现] 代理端注册名: " + name + " (端口匹配 " + port + ")");
                                    break;
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                        break;
                    }
                    case PAPI_RESOLVE: {
                        String requestId = msg.field(0);
                        String playerName = msg.field(1);
                        String text = msg.field(2);
                        Bukkit.getScheduler().runTask(this, () -> {
                            String resolved = org.windy.xingtubot.bukkit.util.PapiResolver.resolve(playerName, text);
                            sendBridgeResponse(org.windy.xingtubot.common.bridge.BridgeCodec.encode(
                                    org.windy.xingtubot.common.bridge.CrossServerProtocol.Type.PAPI_RESULT,
                                    requestId, resolved));
                        });
                        break;
                    }
                    default:
                        break; // 其他类型（auth 相关）交给 xt-auth 的 SpigotBridge 处理
                }
            });
        }

        // 接线主动消息
        if (qqBot != null) {
            QqOpenApiClient api = qqBot.getApi();
            if (api != null) {

                if (qqSendCommand != null) qqSendCommand.setApiClient(api);

                if (spigotCommandHandler != null) {
                    spigotCommandHandler.getProactiveSender().bind(api);
                }
                if (spigotCommandHandler != null && spigotCommandHandler.getService() != null) {
                    spigotCommandHandler.getService().setApiClient(api);
                }
                getLogger().info("✅ 主动消息已启用");
            }
        }

        if (BotRuntimeState.isDebugEnabled()) {
            printConfigSummary(config, slave);
        }
    }

    private void printConfigSummary(FileConfiguration config, boolean slave) {
        for (String line : Texts.banner(
                getDescription().getVersion(), "Bukkit", "/xtb help")) {
            getLogger().info(line);
        }
        String roleDesc = slave ? "手脚（代理接管）" : "本地大脑";
        getLogger().info(Texts.statusLine("角色", roleDesc));
        String appId = config.getString("openapi-app-id", "");
        String masked = appId.length() > 4 ? appId.substring(0, 4) + "****" : "未配置";
        getLogger().info(Texts.statusLine("AppID", masked));
        getLogger().info(Texts.statusLine("监听", "mention"));
        getLogger().info(Texts.statusLine("跨服", "已启用"));
    }


    /**
     * 部署拓扑判定：本服是否为「手脚」（挂在代理后面，由代理大脑统一接管 bot）。
     * 纯自动探测，无需配置。检测到代理（Velocity/BungeeCord）→ 手脚；否则 → 大脑。
     */
    private boolean resolveSlave(FileConfiguration config) {
        return ProxyDetector.isBehindProxy(this);
    }

    /**
     * 初始化 OpenID 昵称缓存：L1 内存 + L2 JSON 文件。
     */
    private void initOpenidNameCache() {
        OpenidNameRepository repo = new JsonOpenidNameRepository(
                new java.io.File(getDataFolder(), "openid_names.json"),
                msg -> getLogger().info(msg));
        OpenidNameCache.getInstance().init(repo);
    }

    @Override
    public void onDisable() {
        // 刷盘 OpenID 昵称缓存
        OpenidNameCache.getInstance().shutdown();
        if (spigotCommandHandler != null) {
            spigotCommandHandler.shutdown();
        }
        if (gatewayClient != null) {
            gatewayClient.stop();
        }
        if (redisHolder != null) {
            redisHolder.close();
        }
        getLogger().info("插件已关闭");
    }

    private void startBot(FileConfiguration config) {
        BotConfig cfg = new SpigotConfig(config);
        // 如果未配置 app-id，自动进入扫码接入流程
        String appId = config.getString("openapi-app-id", "").trim();
        if (appId.isEmpty()) {
            getLogger().info("未配置 openapi-app-id，启动扫码接入流程...");
            QQOnboard onboard = new QQOnboard(new SpigotBotLogger(getLogger()));
            QQOnboard.ScanResult result = onboard.run();
            if (result != null) {
                config.set("openapi-app-id", result.appId);
                config.set("openapi-client-secret", result.clientSecret);
                saveConfig();
                reloadConfig();
                config = getConfig();
                cfg = new SpigotConfig(config);
                getLogger().info("✅ 凭据已写入 config.yml，App ID: " + result.appId);
            } else {
                getLogger().severe("扫码接入失败或超时，请手动填写 openapi-app-id 和 openapi-client-secret 到 config.yml");
                return;
            }
        }
        BotLauncher.GatewayResult gw = BotLauncher.buildGateway(
                cfg, new SpigotAdapter(this), new SpigotBotLogger(getLogger()), this::dispatchToBukkit);
        if (gw != null) {
            qqBot = gw.bot;
            gatewayClient = gw.gatewayClient;
            gatewayClient.setOnBotNameResolved(name ->
                    getLogger().info("✅ 机器人昵称已自动获取: " + name));
            gatewayClient.start();
            getLogger().info("通信模式 = gateway（QQ 官方 WebSocket 网关）已启动");
        } else {
            getLogger().severe("Gateway 模式配置不全，请检查 openapi-app-id / openapi-client-secret");
        }
    }

    private void dispatchToBukkit(BotMessageEvent e) {
        // 追踪最近活跃的群（供 /qq 命令 + 游戏聊天转发用）
        String gid = e.getConversationId();
        boolean groupEvent = isGroupEvent(e);
        if (groupEvent && !isAllowedGroup(gid)) {
            if (BotRuntimeState.isDebugEnabled()) {
                getLogger().info("[QQ] Skip non-allowed group event: group=" + gid + " type=" + e.getEventType());
            }
            return;
        }
        if (groupEvent && gid != null && qqSendCommand != null) {
            qqSendCommand.setDefaultGroupOpenid(gid);
        }
        Bukkit.getScheduler().runTask(this, () -> {
            GuildMessageEvent event = new GuildMessageEvent(
                    e.getConversationId(), e.getSenderId(), e.getMessage(),
                    e.getReply(), e.getUsername(), e.getEventType());
            event.setImageUrls(e.getImageUrls()); // 透传群图片 URL，供群服互联拼 ChatImage 码
            setLastEvent(event);
            Bukkit.getPluginManager().callEvent(event);
        });
    }

    private boolean isGroupEvent(BotMessageEvent event) {
        String type = event.getEventType();
        if (type != null && type.startsWith("GROUP_")) {
            return true;
        }
        String gid = event.getConversationId();
        String uid = event.getSenderId();
        return gid != null && uid != null && !gid.equals(uid);
    }

    private boolean isAllowedGroup(String gid) {
        if (gid == null || gid.isEmpty()) {
            return true;
        }
        List<String> configured = getConfig().getStringList("allowed-groups");
        if (configured == null || configured.isEmpty()) {
            return true;
        }
        Set<String> allowed = new HashSet<>();
        for (String group : configured) {
            if (group == null) continue;
            String trimmed = group.trim();
            if (trimmed.isEmpty()) continue;
            if ("*".equals(trimmed)) {
                return true;
            }
            allowed.add(trimmed);
        }
        return allowed.isEmpty() || allowed.contains(gid);
    }

    /** 运行时解析本服名称：优先代理端注册名（握手后可用），回退配置值。 */
    private String resolveServerName() {
        String proxy = org.windy.xingtubot.common.runtime.BotRuntimeState.getProxyServerName();
        if (proxy != null && !proxy.isEmpty()) return proxy;
        return getConfig().getString("server-name", "server");
    }

    /** 通过 Redis 信道发送响应（PluginMessage + Redis 双发）。 */
    private void sendBridgeResponse(byte[] data) {
        if (redisHolder != null) {
            redisHolder.channel.broadcast(data);
            return;
        }
        // 回退 PluginMessage（需要在线玩家作为载体）
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier != null && carrier.isOnline()) {
            carrier.sendPluginMessage(this,
                    org.windy.xingtubot.common.bridge.CrossServerProtocol.CHANNEL, data);
        }
    }

    private void registerCommands() {
        CommandHandler handler = new CommandHandler(this);
        getCommand("xtb").setExecutor(handler);
        getCommand("xtb").setTabCompleter(handler);

        qqSendCommand = new QQSendCommand("xingtubot.qq.send");
        getCommand("qq").setExecutor(qqSendCommand);
        getCommand("qq").setTabCompleter(qqSendCommand);
    }

    private void enableModules(FileConfiguration config, boolean slave) {
        // 一切功能由附属扩展插件（xt-*）提供，主插件只做核心框架。
        // xt-auth: 白名单+登录 | xt-chatlink: 群服互联 | xt-group: 迎送+自定义 | xt-fun: 娱乐
        // xt-modquery: 模组工具 | xt-ai: AI 对话 | xt-github: GitHub 追踪
        spigotCommandHandler = new SpigotCommandHandler(this);
        // 部署拓扑由框架在此一次性算定并写入宿主，供附属扩展（xt-auth 等）只读，
        // 杜绝扩展各自用 ProxyDetector 重复判定大脑/手脚。slave 取反即为「是否大脑」。
        spigotCommandHandler.getHost().setBrain(!slave);
        spigotCommandHandler.getHost().registerService("core.allowed-groups",
                (java.util.function.Supplier<java.util.List<String>>) () -> getConfig().getStringList("allowed-groups"));
        // 敏感词过滤（全局单例，供 xt-chatlink / xt-ai 等扩展消费）
        SensitiveFilter sf =
                SensitiveFilter.fromConfig(
                        new SpigotConfig(getConfig()), "sensitive-filter",
                        new SpigotBotLogger(getLogger()));
        spigotCommandHandler.getHost().registerService(
                SensitiveFilter.class, sf);
    }

    private void printBanner() {
        for (String line : Texts.banner(getDescription().getVersion(), "Bukkit", "/xtb help")) {
            getLogger().info(line);
        }
    }

    public GuildMessageEvent getLastEvent() {
        return lastEvent;
    }

    public void setLastEvent(GuildMessageEvent event) {
        this.lastEvent = event;
    }

    public static XingtuBot getInstance() {
        return instance;
    }

    public void log(String message) {
        if (BotRuntimeState.isDebugEnabled()) {
            getLogger().info("[调试模式] " + message);
        }
    }
}
