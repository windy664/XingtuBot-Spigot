package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.api.QqOpenApiClient;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.bot.BotLauncher;
import org.windy.xingtubot.common.bot.QQOnboard;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.poll.JdbcOpenidNameRepository;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.poll.OpenidNameRepository;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.util.Pretty;

public final class XingtuBot extends JavaPlugin implements Listener {

    private QqBot qqBot;
    private QQGatewayClient gatewayClient;
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

        // 跨服 Redis 信道（通用基础设施，配置在核心 config）：核心创建并注册到服务总线，
        // 供 xt-auth 子服侧 SpigotBridge（slave）取用；无玩家在线也能与代理大脑双向通信。
        redisHolder = org.windy.xingtubot.common.bridge.CrossServerChannelFactory.create(
                new SpigotConfig(config), true, new SpigotBotLogger(getLogger()));
        if (redisHolder != null && spigotCommandHandler != null) {
            spigotCommandHandler.getHost().registerService(
                    org.windy.xingtubot.common.bridge.CrossServerChannel.class, redisHolder.channel);
        }

        // 接线主动消息
        if (qqBot != null) {
            QqOpenApiClient api = qqBot.getApi();
            if (api != null) {
                if (qqSendCommand != null) qqSendCommand.setApiClient(api);
                // 主动消息惰性句柄填实（附属插件 ProactiveSender 共享同一句柄）
                if (spigotCommandHandler != null) {
                    spigotCommandHandler.getProactiveSender().bind(api);
                }
                if (spigotCommandHandler != null && spigotCommandHandler.getXingtuService() != null) {
                    spigotCommandHandler.getXingtuService().setApiClient(api);
                }
                getLogger().info("✅ 主动消息已启用");
            }
        }

        printConfigSummary(config, slave);
    }

    private void printConfigSummary(FileConfiguration config, boolean slave) {
        getLogger().info("");
        getLogger().info("─────────────  昕途机器人 · 启动摘要  ─────────────");

        // ── 通信 ──
        section("📡 通信");
        boolean botOn = !slave && BotLauncher.resolveMode(new SpigotConfig(config)) != BotLauncher.Mode.OFF;
        String roleDesc = slave ? "（手脚 · 不跑 bot）" : (botOn ? "（本地大脑）" : "（off · 不跑 bot）");
        kv("角色", config.getString("server-role", "auto") + roleDesc);
        if (botOn) {
            String appId = config.getString("openapi-app-id", "");
            String masked = appId.length() > 4 ? appId.substring(0, 4) + "****" : "(未配置)";
            kv("AppID", masked);
            java.util.List<String> groups = config.getStringList("allowed-groups");
            kv("群白名单", groups.isEmpty() || groups.contains("*") ? "全部群" : groups.toString());
        }
        kv("监听模式", config.getString("listen-mode", "mention"));
        kv("调试模式", onOff(config.getBoolean("debug", false)));

        // ── 部署（白名单等功能由 XingtuBot-Auth 附属提供，状态见其自身日志/config）──
        section("🖥 部署");
        kv("角色", slave ? "手脚模式 slave（由代理大脑主导）" : "本地模式（本机跑 bot）");
        kv("存储", config.getString("storage-type", "json"));

        // ── 功能扩展（群服互联/模组/AI/迎送/娱乐等均由附属插件提供，各自独立 config）──
        section("🧩 功能扩展");
        String[][] exts = {
                {"XingtuBot-Auth", "白名单+登录"},
                {"XingtuBot-Chatlink", "群服互联"},
                {"XingtuBot-Group", "迎送+自定义"},
                {"XingtuBot-Fun", "娱乐"},
                {"XingtuBot-Modquery", "模组工具"},
                {"XingtuBot-AI", "AI 对话"},
                {"XingtuBot-Github", "项目追踪"},
        };
        for (String[] ext : exts) {
            boolean installed = Bukkit.getPluginManager().getPlugin(ext[0]) != null;
            kv(ext[1], installed ? "已装 (" + ext[0] + ")" : "未安装");
        }

        getLogger().info("──────────────────────────────────────────────────");
    }

    /** 分组标题。 */
    private void section(String title) {
        getLogger().info("  " + title);
    }

    /** 「键值对」行：键按显示宽度对齐到 14 格，再接值。 */
    private void kv(String label, String value) {
        getLogger().info("     " + Pretty.padEnd(label, 14) + value);
    }

    private static String onOff(boolean on) {
        return on ? "开" : "关";
    }

    /**
     * 部署拓扑判定：本服 bot 由谁跑（与白名单无关，是「单机/手脚」的拓扑选择）。
     *   local/standalone = 本机自己跑 bot；slave = 挂代理后由代理大脑统一接管，本机 bot 禁用；
     *   auto = 探测到代理则 slave，否则 local。兼容旧键 whitelist-role。
     */
    private boolean resolveSlave(FileConfiguration config) {
        String role = config.getString("server-role",
                config.getString("whitelist-role", "auto")).trim().toLowerCase();
        if (role.equals("slave")) return true;
        if (role.equals("local") || role.equals("standalone")) return false;
        return org.windy.xingtubot.bukkit.util.ProxyDetector.isBehindProxy(this);
    }

    /**
     * 初始化 OpenID 昵称缓存：L1 内存 + L2 DB（复用 binding 的存储类型配置）。
     */
    private void initOpenidNameCache() {
        FileConfiguration config = getConfig();
        String storageType = config.getString("storage-type", "json").trim().toLowerCase();
        OpenidNameRepository repo;

        switch (storageType) {
            case "mysql":
                repo = JdbcOpenidNameRepository.mysql(
                        config.getString("mysql-host", "127.0.0.1"),
                        config.getInt("mysql-port", 3306),
                        config.getString("mysql-database", "xingtubot"),
                        config.getString("mysql-user", "root"),
                        config.getString("mysql-password", ""),
                        msg -> getLogger().info(msg));
                break;
            case "sqlite":
            default:
                repo = JdbcOpenidNameRepository.sqlite(
                        new java.io.File(getDataFolder(), "openid_names.db").getAbsolutePath(),
                        msg -> getLogger().info(msg));
                break;
        }

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
        switch (BotLauncher.resolveMode(cfg)) {
            case OFF:
                getLogger().info("通信模式 = off，机器人通信未启用。");
                return;
            case GATEWAY:
                // 如果未配置 app-id，自动进入扫码接入流程
                String appId = config.getString("openapi-app-id", "").trim();
                if (appId.isEmpty()) {
                    getLogger().info("未配置 openapi-app-id，启动扫码接入流程...");
                    QQOnboard onboard = new QQOnboard(new SpigotBotLogger(getLogger()));
                    QQOnboard.ScanResult result = onboard.run();
                    if (result != null) {
                        // 写入 config.yml 并重载
                        config.set("openapi-app-id", result.appId);
                        config.set("openapi-client-secret", result.clientSecret);
                        saveConfig();
                        reloadConfig(); // 从磁盘重载，确保内存中的 config 一致
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
                    // 机器人昵称由 QQ API 自动写入 BotIdentity（QQGatewayClient 内部已处理）；此处仅记日志
                    gatewayClient.setOnBotNameResolved(name ->
                            getLogger().info("✅ 机器人昵称已自动获取: " + name));
                    gatewayClient.start();
                    getLogger().info("通信模式 = gateway（QQ 官方 WebSocket 网关）已启动");
                } else {
                    getLogger().severe("Gateway 模式配置不全，请检查 openapi-app-id / openapi-client-secret");
                }
                return;
            default:
                getLogger().severe("未知的 server-role，请检查配置。");
        }
    }

    private void dispatchToBukkit(BotMessageEvent e) {
        // 追踪最近活跃的群（供 /qq 命令 + 游戏聊天转发用）
        String gid = e.getGuildId();
        if (gid != null && qqSendCommand != null) {
            qqSendCommand.setDefaultGroupOpenid(gid);
        }
        Bukkit.getScheduler().runTask(this, () -> {
            GuildMessageEvent event = new GuildMessageEvent(
                    e.getGuildId(), e.getFormId(), e.getMessage(), e.getReplier(), e.getUsername(), e.getEventType());
            event.setImageUrls(e.getImageUrls()); // 透传群图片 URL，供群服互联拼 ChatImage 码
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
        // 一切功能由附属扩展插件（xt-*）提供，主插件只做核心框架。
        // xt-auth: 白名单+登录 | xt-chatlink: 群服互联 | xt-group: 迎送+自定义 | xt-fun: 娱乐
        // xt-modquery: 模组工具 | xt-ai: AI 对话 | xt-github: GitHub 追踪
        spigotCommandHandler = new SpigotCommandHandler(this);
        // 部署拓扑由框架在此一次性算定并写入宿主，供附属扩展（xt-auth 等）只读，
        // 杜绝扩展各自用 ProxyDetector 重复判定大脑/手脚。slave 取反即为「是否大脑」。
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

    public void log(String message) {
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[调试模式] " + message);
        }
    }
}
