package org.windy.xingtubot.ext.xtauth;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.module.AuthModule;
import org.windy.xingtubot.velocity.VelocityBridge;
import org.windy.xingtubot.velocity.VelocityDirectAuthAdapter;
import org.windy.xingtubot.velocity.VelocityJoinQrMap;
import org.windy.xingtubot.velocity.VelocityPlayerLock;
import org.windy.xingtubot.velocity.XingtuBotVelocity;

import java.nio.file.Path;

/**
 * 白名单+登录扩展 · Velocity 主类。
 */
@Plugin(
        id = "xingtubot-auth",
        name = "XingtuBot-Auth",
        version = "2.2.1",
        authors = {"风吟"},
        dependencies = {
                @Dependency(id = "xingtubotvelocity"),
        }
)
public class AuthVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final PluginContainer pluginContainer;
    private BotModule module;
    private VelocityPlayerLock lockManager;

    @Inject
    public AuthVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir,
                              PluginContainer pluginContainer) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        this.pluginContainer = pluginContainer;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        // packetevents 内置进本 jar：自行初始化（不再依赖外部 packetevents 插件）。
        // 依 packetevents 官方 Velocity 生命周期：setAPI + load + init 均在 ProxyInitializeEvent。
        if (com.github.retrooper.packetevents.PacketEvents.getAPI() == null) {
            com.github.retrooper.packetevents.PacketEvents.setAPI(
                    io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder.build(
                            proxy, pluginContainer, logger, dataDir));
            com.github.retrooper.packetevents.PacketEvents.getAPI().load();
        }
        if (!com.github.retrooper.packetevents.PacketEvents.getAPI().isInitialized()) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().init();
        }
        XingtuBotHost host = proxy.getPluginManager().getPlugin("xingtubotvelocity")
                .flatMap(PluginContainer::getInstance)
                .filter(p -> p instanceof XingtuBotHostProvider)
                .map(p -> ((XingtuBotHostProvider) p).getHost())
                .orElse(null);

        BotLogger botLogger = new BotLogger() {
            @Override public void info(String msg) { logger.info(msg); }
            @Override public void warn(String msg) { logger.warn(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(dataDir.toFile(), getClass().getClassLoader());

        // 游戏内锁定文案：从独立 messages.yml 覆盖默认（首启自动释放）
        org.windy.xingtubot.common.whitelist.LockMessages.load(
                new YamlBotConfig(dataDir.toFile(), getClass().getClassLoader(), "messages.yml")::getString);

        // 取核心 VelocityBridge：它持有认证适配器（DO_LOGIN/DO_REGISTER 下发通道），
        // 须在 enable 之前拿到并注入 AuthModule，否则大脑侧 BindingService 的 auth 为 null，
        // 群里「登录」/「绑定」命中后无法驱动子服解锁。
        VelocityBridge bridge = proxy.getPluginManager().getPlugin("xingtubotvelocity")
                .flatMap(PluginContainer::getInstance)
                .filter(p -> p instanceof XingtuBotVelocity)
                .map(p -> ((XingtuBotVelocity) p).getVelocityBridge())
                .orElse(null);

        // ===== packetevents 登录锁预初始化 =====
        // 必须在 ExtensionBootstrap.enable() 之前创建 lockManager 和 directAuth，
        // 因为 BindingService 在 enable() 时创建，会捕获当时的 authAdapter。
        // 如果 enable() 之后才设置，BindingService 已经用了旧的 PluginMessageAuthAdapter。
        VelocityDirectAuthAdapter directAuth = null;
        if (bridge != null && packetEventsAvailable()) {
            // 先创建一个临时的 lockManager（BindingService 还没创建，先传 null）
            // 在 enable() 拿到 BindingService 后会重新初始化完整的 lockManager
            lockManager = new VelocityPlayerLock(proxy, this, null);
            directAuth = new VelocityDirectAuthAdapter(proxy, lockManager);
            bridge.setLockManager(lockManager);
        }

        AuthModule authModule = new AuthModule(proxy, dataDir);
        if (directAuth != null) {
            // 用 packetevents 直通适配器：login/register 在 Velocity 侧直接解锁
            authModule.setAuthAdapter(directAuth);
        } else if (bridge != null) {
            authModule.setAuthAdapter(bridge.getAuthAdapter());
        } else {
            logger.warn("[Auth] 未找到核心 VelocityBridge（server-role=off？）：白名单解锁将不可用。");
        }
        module = ExtensionBootstrap.enable(host, authModule, config, botLogger, dataDir.toFile());

        // ===== packetevents 登录锁补全 =====
        // enable() 后 BindingService 已注册到服务总线，补全 lockManager 的 BindingService 引用。
        if (lockManager != null) {
            org.windy.xingtubot.common.binding.BindingService bindingService =
                    host != null ? host.getService(org.windy.xingtubot.common.binding.BindingService.class) : null;
            if (bindingService != null) {
                lockManager.setBindingService(bindingService);

                // QQ 登记成功后发加群二维码地图
                lockManager.setOnCodeIssued(name ->
                        VelocityJoinQrMap.giveIfEnabled(proxy, config, name));

                // 注册 packetevents 包拦截器
                com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                        .registerListener(new org.windy.xingtubot.common.whitelist.LockPacketListener(lockManager));

                logger.info("[Auth] packetevents 登录锁已启用（Velocity 端包级拦截 + 拓扑门禁）");
            } else {
                logger.warn("[Auth] BindingService 未就绪，packetevents 登录锁未启用。");
            }
        }

        // 注册「未绑定进服 → 加群二维码」回调（白名单 QR 完整归属 xt-auth，群号/链接读 xt-auth 自己的 config）。
        // 跨服 Redis 信道由核心创建并注入到 bridge（通用基础设施，配置在核心 config），此处不再处理。
        if (bridge != null) {
            // 加群二维码挂在「登记QQ成功后」发，而非一进服(输QQ阶段)就发——避免误导玩家以为要先扫码
            bridge.setOnCodeIssued(name -> VelocityJoinQrMap.giveIfEnabled(proxy, config, name));

            // IP 绑定的自动登录信任期（分钟）：登录后退出，同 IP 窗口内重进自动登录。0=关闭。默认 720（12h）。
            bridge.setAutoLoginWindowMillis(
                    config.getInt("auto-login-window-minutes", 720) * 60_000L);
            // 信任期持久化：复用绑定库同一 storage-type（json/sqlite/mysql），跨重启存活。
            // Velocity 端跑 bot 即代理大脑，isBrain=true（sqlite 单端直连不锁库）。
            try {
                org.windy.xingtubot.common.binding.AutoLoginRepository autoRepo =
                        org.windy.xingtubot.common.binding.AutoLoginStorageFactory.create(
                                config, true, dataDir.toFile(), botLogger::warn);
                if (autoRepo != null) bridge.setAutoLoginRepository(autoRepo);
            } catch (Throwable t) {
                logger.warn("[Auth] 自动登录信任期持久化初始化失败，退回内存（重启失效）: " + t.getMessage());
            }

            // 登录提示持续循环：lockManager 为 null 时用旧的 bridge 提醒；lockManager 非 null 时由它自己管。
            if (lockManager == null && config.getBoolean("login-reminder-enable", true)) {
                bridge.startLoginReminder(config.getInt("login-reminder-seconds", 3));
            }

            // 已绑定但需登录的玩家进服 → 在群里发「免密登录」按钮卡片（含玩家名 + 省级地区）。
            // 绑定的 QQ 点按钮即触发「登录」走 WhitelistHandler；免密信任期内的玩家不会走到这（已自动登录）。
            if (host != null && config.getBoolean("login-button-announce", true)) {
                bridge.setOnNeedLogin(name -> announceLoginButton(host, config, name));
            }
        }
    }

    /** 已绑定玩家需登录时，在目标群发带「登录」按钮的免密登录卡片。地区查询走异步。 */
    private void announceLoginButton(XingtuBotHost host, YamlBotConfig config, String playerName) {
        org.windy.xingtubot.common.module.capability.ProactiveSender sender =
                host.getService(org.windy.xingtubot.common.module.capability.ProactiveSender.class);
        if (sender == null || !sender.isReady()) return;

        java.util.List<String> groups = resolveGroups(config);
        if (groups.isEmpty()) return;

        final String loginWord = config.getString("login-prompt", "登录");
        final String btnLabel = config.getString("login-button-label", "✅ 同意登录");
        final boolean showRegion = config.getBoolean("login-announce-region", true);
        // 仅取 IP 字符串用于省级定位，不留存；地区查询发网络请求，放异步线程。
        final String ip = proxy.getPlayer(playerName)
                .map(com.velocitypowered.api.proxy.Player::getRemoteAddress)
                .map(addr -> addr.getAddress() != null ? addr.getAddress().getHostAddress() : null)
                .orElse(null);

        proxy.getScheduler().buildTask(this, () -> {
            String region = showRegion ? org.windy.xingtubot.common.util.IpGeo.province(ip) : "";
            String card = buildLoginCard(playerName, region, loginWord);
            // 按钮【仅本人 openid 可点】：其他人点不动、收不到交互，从源头防刷屏。
            String openid = resolveOpenid(host, playerName);
            String keyboard = org.windy.xingtubot.common.util.Keyboards.callbackForUser(
                    btnLabel, loginWord, openid);
            for (String g : groups) {
                sender.sendGroupMarkdownKeyboard(g, card, keyboard);
            }
        }).schedule();
    }

    /** 取玩家绑定的 openid（用于把登录按钮限定为只有本人可点）；取不到返回 null（退化为所有人可点）。 */
    private static String resolveOpenid(XingtuBotHost host, String player) {
        try {
            org.windy.xingtubot.common.binding.BindingRepository repo =
                    host.getService(org.windy.xingtubot.common.binding.BindingRepository.class);
            if (repo != null) {
                org.windy.xingtubot.common.binding.BindingEntry e = repo.findByPlayer(player);
                if (e != null) return e.openid;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 免密登录卡片：玩家名 +（可选）省级地区 + 操作指引。
     *
     * <p>文案刻意把「直接回复{@code loginWord}」写成可独立成立的兜底——主动消息的内联按钮可能因权限
     * 不下发（届时卡片只剩文字、无按钮），此时玩家照样能按这句话在群里回复关键词完成登录。
     */
    private static String buildLoginCard(String player, String region, String loginWord) {
        StringBuilder sb = new StringBuilder();
        sb.append(LockMessages.get("group-login-card-title"));
        sb.append(LockMessages.get("group-login-card-player")).append(player).append("\n");
        sb.append(LockMessages.format("group-login-card-tip", "{login}", loginWord));
        return sb.toString();
    }

    /** 目标群：allowed-groups；含 "*" 或留空 → 全部已知群（KnownGroupStore）。 */
    private static java.util.List<String> resolveGroups(YamlBotConfig config) {
        java.util.List<String> allowed = config.getStringList("allowed-groups");
        boolean all = allowed.isEmpty();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String g : allowed) {
            if (g == null || g.trim().isEmpty()) continue;
            if ("*".equals(g.trim())) { all = true; continue; }
            out.add(g.trim());
        }
        if (all) {
            return new java.util.ArrayList<>(
                    org.windy.xingtubot.common.queue.KnownGroupStore.getInstance().all());
        }
        return out;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ExtensionBootstrap.disable(module);
    }

    /** 检查 packetevents 是否可用（运行期软依赖，没装时静默降级）。 */
    private static boolean packetEventsAvailable() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            return com.github.retrooper.packetevents.PacketEvents.getAPI() != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
