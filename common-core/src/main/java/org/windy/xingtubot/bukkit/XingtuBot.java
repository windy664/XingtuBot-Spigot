package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
                if (spigotCommandHandler != null && spigotCommandHandler.getService() != null) {
                    spigotCommandHandler.getService().setApiClient(api);
                }
                getLogger().info("✅ 主动消息已启用");
            }
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
            String appId = config.getString("openapi-app-id", "");
            String masked = appId.length() > 4 ? appId.substring(0, 4) + "****" : "未配置";
            getLogger().info("▌ AppID    " + masked);
        }
        getLogger().info("▌ 监听     " + config.getString("listen-mode", "mention"));
        getLogger().info("▌ 跨服     " + (botOn ? "已启用" : "未启用"));
        getLogger().info("▌ 存储     JSON");
        getLogger().info("▌ ✔ 已启动  输入 /xtb help 查看命令");
        getLogger().info("");
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
                    // 机器人昵称由 QQ API 自动写入 BotRuntimeState（QQGatewayClient 内部已处理）；此处仅记日志
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
        String gid = e.getConversationId();
        boolean groupEvent = isGroupEvent(e);
        if (groupEvent && !isAllowedGroup(gid)) {
            if (getConfig().getBoolean("debug", false)) {
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
