package org.windy.xingtubot.module;

import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.ext.xtauth.binding.BindingServiceImpl;
import org.windy.xingtubot.common.binding.BindingStorageFactory;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.handler.impl.BindingAdminHandler;
import org.windy.xingtubot.common.handler.impl.WhitelistHandler;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.File;

/**
 * 白名单+登录模块：绑定系统 + QQ 群侧白名单处理。
 *
 * <p>平台原生代码（SpigotBridge / VelocityBridge）由各平台主类在 onEnable 之后初始化。
 *
 * <p>注意：跨服信道（Redis/PluginMessage）是【核心框架】的通用基础设施，由核心创建并注入到桥
 * 或注册到服务总线（见核心 {@code CrossServerChannelFactory}）；本附属模块不再自建 Redis。
 */
public final class AuthModule implements BotModule {

    private final Object platformPlugin;
    private final Object platformDataDir;
    // 代理大脑（Velocity/BungeeCord）侧的认证适配器：群里「登录」/「绑定」命中后，
    // 通过它把 DO_LOGIN/DO_REGISTER 下发到玩家所在子服解锁。由平台主类在 enable 前注入。
    // 本地 Bukkit 大脑模式不用它（绑定/解锁由平台 WhitelistModule 接管），保持 null。
    private AuthAdapter authAdapter;
    // 本地白名单模式（单机 Bukkit 即大脑）：绑定 + 锁 + 群验证由平台侧 WhitelistModule 接管，
    // 本模块不再重复创建 store/service/WhitelistHandler（否则会有两套库 + 双重处理）。
    private final boolean localWhitelist;

    /** Bukkit 平台构造。 */
    public AuthModule(Object bukkitPlugin) {
        this(bukkitPlugin, false);
    }

    /** Bukkit 平台构造（localWhitelist=true 时把绑定交给平台 WhitelistModule）。 */
    public AuthModule(Object bukkitPlugin, boolean localWhitelist) {
        this.platformPlugin = bukkitPlugin;
        this.platformDataDir = null;
        this.localWhitelist = localWhitelist;
    }

    /** Velocity 平台构造。 */
    public AuthModule(Object proxyServer, Object dataDir) {
        this.platformPlugin = proxyServer;
        this.platformDataDir = dataDir;
        this.localWhitelist = false;
    }

    /**
     * 注入代理大脑侧的认证适配器（DO_LOGIN/DO_REGISTER 下发通道）。
     * 必须在 {@link #onEnable} 之前调用（即 ExtensionBootstrap.enable 之前）。
     */
    public void setAuthAdapter(AuthAdapter authAdapter) {
        this.authAdapter = authAdapter;
    }

    @Override
    public String name() {
        return "auth";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        BotConfig config = ctx.config();
        BotLogger logger = ctx.logger();

        if (!config.getBoolean("whitelist-enable", true)) {
            logger.info("[Auth] 白名单功能已禁用。");
            return;
        }

        // ===== 绑定系统 =====
        // 本地白名单模式：绑定/锁/群验证由平台 WhitelistModule 接管（它会把真实 store 注册到总线），
        // 本模块跳过，避免两套库 + 双重 QQ 处理。
        if (localWhitelist) {
            logger.info("[Auth] 本地白名单模式：绑定与群验证交由平台 WhitelistModule 接管。");
        } else {
            // AppID 惰性解析：不在此处一次性捕获——尤其 Velocity，核心 setApiClient 在 init 后段才发生，
            // 此刻 getBotAppId() 可能还是空。改为每次绑定时经 ctx 现取 XingtuBotService.getBotAppId()，
            // 玩家发「绑定」时核心早已就绪。否则空值固化 → openid 头像永远回落企鹅 → 所有绑定失败。
            java.util.function.Supplier<String> appIdSupplier = () -> {
                org.windy.xingtubot.common.api.XingtuBotService s =
                        ctx.getService(org.windy.xingtubot.common.api.XingtuBotService.class);
                return s != null ? s.getBotAppId() : "";
            };
            if (appIdSupplier.get() == null || appIdSupplier.get().isEmpty()) {
                logger.warn("[Auth] 启动时 openapi-app-id 暂为空（核心 bot 可能还没起好）——已改惰性获取，"
                        + "绑定时会重取；若届时仍为空，请检查 openapi-app-id 配置。");
            }

            File dataFolder = ctx.dataFolder();

            BindingRepository store = BindingStorageFactory.create(config, false, dataFolder, logger::warn);
            // authAdapter 由平台主类注入（代理大脑：Velocity/BungeeCord 的 PluginMessageAuthAdapter）；
            // 没注入则为 null（loginByGroup/bindByAvatar 会回退为「服务不可用/玩家不在线」提示）。
            // 仅在代理上下文（platformDataDir != null）告警——Bukkit 子服(slave)本就不处理群消息，无需适配器。
            if (authAdapter == null && platformDataDir != null) {
                logger.warn("[Auth] 未注入认证适配器：群里「登录」/「绑定」将无法驱动子服解锁。"
                        + "请确认核心 Bridge 已就绪（server-role 非 off）。");
            }
            BindingService bindingService = new BindingServiceImpl(store, authAdapter, appIdSupplier, logger::warn);
            bindingService.setBindingPrompt(config.getString("binding-prompt", "绑定"));
            bindingService.setMaxBindAttempts(config.getInt("bind-max-attempts", 5));
            // 「IP 绑定的自动登录」信任期由代理大脑(VelocityBridge)持有（它才有玩家 IP），
            // 见 AuthVelocityPlugin.setAutoLoginWindowMillis；本模块不再处理。
            bindingService.setSuccessTemplates(
                    config.getString("messages.bind-success", ""),
                    config.getString("messages.login-success", ""));

            ctx.registerService(BindingRepository.class, store);
            ctx.registerService(BindingService.class, bindingService);

            // ===== WhitelistHandler =====
            if (ctx.registry() != null) {
                ctx.registry().register(new WhitelistHandler(bindingService, config));
            }
        }

        // ===== 超管绑定管理命令（绑定列表/查绑定/解绑）=====
        // 两种部署通用：BindingRepository 懒取自服务总线（本地模式由 WhitelistModule 注册，大脑模式由上面注册）。
        if (ctx.registry() != null) {
            ctx.registry().register(new BindingAdminHandler(
                    () -> ctx.getService(BindingRepository.class)));
        }

        String storageType = config.getString("storage-type", "json");
        logger.info("[Auth] 白名单+登录已加载（存储: " + storageType + "）");
    }

    @Override
    public void onDisable() {
        // 跨服信道由核心创建/关闭，本模块无需处理。
    }
}
