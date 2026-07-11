package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.bot.QQOnboard;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.messenger.MessengerConnectionState;
import org.windy.xingtubot.common.messenger.OfficialBotMessenger;
import org.windy.xingtubot.common.messenger.PlatformMessenger;
import org.windy.xingtubot.common.poll.JsonOpenidNameRepository;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.poll.OpenidNameRepository;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.util.Pretty;

public final class XingtuBot extends JavaPlugin implements Listener {

    private QqBot qqBot;
    private QQGatewayClient gatewayClient;
    private PlatformMessenger messenger; // 平台适配器（官方 bot / OB11）
    private org.windy.xingtubot.common.onebot.OneBot11Messenger ob11Messenger;
    private GuildMessageEvent lastEvent;
    private SpigotCommandHandler spigotCommandHandler;
    private QQSendCommand qqSendCommand;
    private org.windy.xingtubot.common.bridge.CrossServerChannelFactory.Holder redisHolder;
    private static XingtuBot instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        printBanner();

        // 初始化消息队列持久化
        PendingMessageQueue.getInstance().init(getDataFolder());
        // 初始化已知群列表持久化（供「推送到全部群」的主动消息使用）
        org.windy.xingtubot.common.queue.KnownGroupStore.getInstance().init(getDataFolder());

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

        // 跨服 Redis 信道
        redisHolder = org.windy.xingtubot.common.bridge.CrossServerChannelFactory.create(
                new SpigotConfig(config), true, new SpigotBotLogger(getLogger()));
        if (redisHolder != null && spigotCommandHandler != null) {
            spigotCommandHandler.getHost().registerService(
                    org.windy.xingtubot.common.bridge.CrossServerChannel.class, redisHolder.channel);
        }

        // 接线主动消息：注入 PlatformMessenger 到惰性句柄和服务实现
        if (messenger != null) {
            if (qqSendCommand != null) qqSendCommand.setMessenger(messenger);
            if (spigotCommandHandler != null) {
                spigotCommandHandler.getProactiveSender().bind(messenger);
            }
            if (spigotCommandHandler != null && spigotCommandHandler.getXingtuService() != null) {
                spigotCommandHandler.getXingtuService().setMessenger(messenger);
            }
            getLogger().info("✅ 主动消息已启用（协议: " + messenger.getClass().getSimpleName() + "）");
        }

        if (config.getBoolean("debug", false)) {
            printConfigSummary(config, slave);
        }
    }

    private void printConfigSummary(FileConfiguration config, boolean slave) {
        boolean botOn = !slave && BotLauncher.resolveMode(new SpigotConfig(config)) != BotLauncher.Mode.OFF;
        getLogger().info("");
        getLogger().info(" ██╗  ██╗██╗███╗   ██╗ ██████╗ ████████╗██╗   ██╗██████╗  ██████╗ ████████╗");
        getLogger().info(" ╚██╗██╔╝██║████╗  ██║██╔════╝ ╚══██╔══╝██║   ██║██╔══██╗██╔═══██╗╚══██╔══╝");
        getLogger().info("  ╚███╔╝ ██║██╔██╗ ██║██║        ██║   ██║   ██║██████╔╝██║   ██║   ██║   ");
        getLogger().info("  ██╔██╗ ██║██║╚██╗██║██║        ██║   ██║   ██║██╔══██╗██║   ██║   ██║   ");
        getLogger().info(" ██╔╝ ██╗██║██║ ╚████║╚██████╗   ██║   ╚██████╔╝██████╔╝╚██████╔╝   ██║   ");
        getLogger().info(" ╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝ ╚═════╝   ╚═╝    ╚═════╝ ╚═════╝  ╚═════╝    ╚═╝   ");
        getLogger().info("");
        getLogger().info("▌ 昕途机器人 v" + getDescription().getVersion());
        String roleDesc = slave ? "（手脚）" : (botOn ? "（本地大脑）" : "（off）");
        getLogger().info("▌ 角色     " + config.getString("server-role", "auto") + roleDesc);
        if (botOn) {
            String protocol = config.getString("qq-protocol", "official");
            getLogger().info("▌ 协议     " + protocol);
            if ("official".equals(protocol)) {
                String appId = config.getString("openapi-app-id", "");
                String masked = appId.length() > 4 ? appId.substring(0, 4) + "****" : "未配置";
                getLogger().info("▌ AppID    " + masked);
            }
        }
        getLogger().info("▌ 监听     " + config.getString("listen-mode", "mention"));
        getLogger().info("▌ 跨服     " + (botOn ? "已启用" : "未启用"));
        getLogger().info("▌ 存储     JSON");
        getLogger().info("▌ ✔ 已启动  输入 /xtb help 查看命令");
        getLogger().info("");
    }


    /**
     * 部署拓扑判定：本服 bot 由谁跑。
     *   local/standalone = 本机自己跑 bot；slave = 挂代理后由代理大脑统一接管；
     *   auto = 探测到代理则 slave，否则 local。
     */
    private boolean resolveSlave(FileConfiguration config) {
        String role = config.getString("server-role",
                config.getString("whitelist-role", "auto")).trim().toLowerCase();
        if (role.equals("slave")) return true;
        if (role.equals("local") || role.equals("standalone")) return false;
        return org.windy.xingtubot.bukkit.util.ProxyDetector.isBehindProxy(this);
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
        if (messenger != null) {
            messenger.close();
        }
        getLogger().info("插件已关闭");
    }

    private void startBot(FileConfiguration config) {
        BotConfig cfg = new SpigotConfig(config);
        switch (BotLauncher.resolveMode(cfg)) {
            case OFF:
                getLogger().info("通信模式 = off，机器人通信未启用。");
                return;
            case GATEWAY:
                startGatewayBot(config, cfg);
                return;
            case ONEBOT11:
                startOnebot11Bot(cfg);
                return;
            default:
                getLogger().severe("未知的通信模式，请检查 qq-protocol 配置。");
        }
    }

    /** 启动 QQ 官方协议（gateway 模式）。 */
    private void startGatewayBot(FileConfiguration config, BotConfig cfg) {
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
            messenger = gw.messenger;
            // 机器人昵称由 QQ API 自动写入 BotIdentity
            gatewayClient.setOnBotNameResolved(name ->
                    getLogger().info("✅ 机器人昵称已自动获取: " + name));
            // gateway 断开时 messenger 状态自动切换
            messenger.setState(MessengerConnectionState.CONNECTING);
            gatewayClient.start();
            getLogger().info("通信模式 = gateway（QQ 官方 WebSocket 网关）已启动");
        } else {
            getLogger().severe("Gateway 模式配置不全，请检查 openapi-app-id / openapi-client-secret");
        }
    }

    /** 启动 OneBot 11 协议。 */
    private void startOnebot11Bot(BotConfig cfg) {
        getLogger().info("通信模式 = onebot11（OneBot 11 协议）");
        BotLauncher.OneBotResult ob11 = BotLauncher.buildOnebot11(
                cfg, new SpigotAdapter(this), new SpigotBotLogger(getLogger()), this::dispatchToBukkit);
        if (ob11 == null) {
            getLogger().severe("OneBot 11 配置不全，请检查 onebot.* 配置项");
            return;
        }
        ob11Messenger = ob11.messenger;
        messenger = ob11.messenger;
        getLogger().info("OneBot 11 网关已就绪，正在连接 " + cfg.getString("onebot.forward-url", "?"));
        ob11.messenger.start();
        getLogger().info("通信模式 = onebot11（正向 WS）已启动");
    }

    private void dispatchToBukkit(BotMessageEvent e) {
        // 追踪最近活跃的群（供 /qq 命令 + 游戏聊天转发用）
        String gid = e.getSessionId();
        if (gid != null && qqSendCommand != null) {
            qqSendCommand.setDefaultGroupOpenid(gid);
        }
        final String fMsg = e.getMessage();
        Bukkit.getScheduler().runTask(this, () -> {
            GuildMessageEvent event = new GuildMessageEvent(
                    e.getGroupId(), e.getFormId(), fMsg, e.getReplier(), e.getUsername(), e.getEventType());
            event.setImageUrls(e.getImageUrls());
            setLastEvent(event);
            Bukkit.getPluginManager().callEvent(event);
        });
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
        spigotCommandHandler = new SpigotCommandHandler(this);
        spigotCommandHandler.getHost().setBrain(!slave);
    }

    private void printBanner() {
        getLogger().info("");
        getLogger().info(" _|      _|  _|_|_|  _|      _|    _|_|_|  _|_|_|_|_|  _|    _|  ");
        getLogger().info("   _|  _|      _|    _|_|    _|  _|            _|      _|    _|  ");
        getLogger().info("     _|        _|    _|  _|  _|  _|  _|_|      _|      _|    _|  ");
        getLogger().info("   _|  _|      _|    _|    _|_|  _|    _|      _|      _|    _|  ");
        getLogger().info(" _|      _|  _|_|_|  _|      _|    _|_|_|      _|        _|_|    ");
        getLogger().info("");
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

    /** 获取当前平台消息适配器实例。 */
    public PlatformMessenger getMessenger() {
        return messenger;
    }

    public void log(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[调试模式] " + message);
        }
    }
}
