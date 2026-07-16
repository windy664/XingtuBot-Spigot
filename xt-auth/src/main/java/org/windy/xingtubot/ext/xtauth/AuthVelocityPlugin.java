package org.windy.xingtubot.ext.xtauth;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
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
import org.windy.xingtubot.velocity.VelocityDirectAuthAdapter;
import org.windy.xingtubot.velocity.VelocityJoinQrMap;
import org.windy.xingtubot.velocity.VelocityPlayerLock;
import org.windy.xingtubot.velocity.VelocityPlayerOps;

import java.nio.file.Path;

/**
 * 白名单+登录扩展 · Velocity 主类。
 *
 * <p>auth 完全自治：lockManager + DirectAuthAdapter + 进服判定 + 登录提醒，全部由本插件管理。
 * 不依赖核心 VelocityBridge 的任何 auth 方法。
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
    private VelocityDirectAuthAdapter directAuth;
    private org.windy.xingtubot.common.binding.BindingService bindingService;

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
        // packetevents 内置进本 jar：自行初始化
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

        // 游戏内锁定文案
        LockMessages.load(new YamlBotConfig(dataDir.toFile(), getClass().getClassLoader(), "messages.yml")::getString);

        // ===== packetevents 登录锁 =====
        if (packetEventsAvailable()) {
            lockManager = new VelocityPlayerLock(proxy, null);
            directAuth = new VelocityDirectAuthAdapter(lockManager, new VelocityPlayerOps(proxy));
        }

        // ===== AuthModule =====
        AuthModule authModule = new AuthModule(proxy, dataDir);
        if (directAuth != null) {
            authModule.setAuthAdapter(directAuth);
        } else {
            logger.warn("[Auth] packetevents 不可用，白名单解锁将不可用。");
        }
        module = ExtensionBootstrap.enable(host, authModule, config, botLogger, dataDir.toFile());

        // ===== enable() 后补全 lockManager =====
        if (lockManager != null && host != null) {
            org.windy.xingtubot.common.binding.BindingService bindingService =
                    host.getService(org.windy.xingtubot.common.binding.BindingService.class);
            if (bindingService != null) {
                this.bindingService = bindingService;
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

        // IP 绑定的自动登录信任期（持久化）
        if (lockManager != null) {
            try {
                org.windy.xingtubot.common.binding.AutoLoginRepository autoRepo =
                        org.windy.xingtubot.common.binding.AutoLoginStorageFactory.create(
                                config, true, dataDir.toFile(), botLogger::warn);
                if (autoRepo != null) lockManager.setAutoLoginRepository(autoRepo);
            } catch (Throwable t) {
                logger.warn("[Auth] 自动登录信任期持久化初始化失败，退回内存: " + t.getMessage());
            }
            lockManager.setAutoLoginWindowMillis(
                    config.getInt("auto-login-window-minutes", 720) * 60_000L);
        }

        // 已绑定但需登录的玩家进服 → 在群里发「免密登录」按钮卡片
        if (lockManager != null && host != null) {
            lockManager.setOnNeedLogin(name -> announceLoginButton(host, config, name));
        }
    }

    // ==================== 进服锁流程（Velocity 自闭环，子服不必装 auth） ====================

    /**
     * 玩家连上子服 → 延迟 500ms 判定并触发锁流程。
     * <p>延迟是给 packetevents/PLAY 态一点余量，确保后续 title/发包能到客户端。
     */
    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        final String name = event.getPlayer().getUsername();
        // 未登录会话才立即锁定：让包监听器尽早捕获真实背包，锁期只给客户端显示空背包。
        // 已登录会话通常是跨子服切换，后端可能已先下发真实背包；这里不再发空背包，避免无缓存可恢复。
        if (lockManager != null && shouldLockImmediately(name)) lockManager.lock(name);
        proxy.getScheduler().buildTask(this, () -> evaluateOnJoin(name))
                .delay(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .schedule();
    }

    private boolean shouldLockImmediately(String player) {
        org.windy.xingtubot.common.binding.BindingService svc = bindingService;
        return svc == null || !svc.isLoggedInSession(player);
    }

    /** 锁定期禁止切服（拓扑级门禁，配合 packetevents 包级拦截，玩家既不能操作也不能切服逃跑）。 */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (lockManager != null && lockManager.isLocked(event.getPlayer().getUsername())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    /** 离线清理:停提醒定时器 + 清 bossbar + 武装自动登录信任期 + 清会话(否则 loggedIn 残留致重进按钮登录被去重静默)。 */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        final com.velocitypowered.api.proxy.Player p = event.getPlayer();
        final String name = p.getUsername();
        if (lockManager != null) lockManager.onDisconnect(name);
        if (bindingService != null) {
            // 退出前若本会话已登录,按当前 IP 武装信任期:同 IP 窗口内重进可免密自动登录。
            if (bindingService.isLoggedInSession(name)) {
                String ip = p.getRemoteAddress() != null && p.getRemoteAddress().getAddress() != null
                        ? p.getRemoteAddress().getAddress().getHostAddress() : null;
                if (ip != null && lockManager != null) lockManager.armAutoLogin(name, ip);
            }
            bindingService.clearSession(name);
        }
    }

    /**
     * 进服判定：按绑定/会话/自动登录状态决定锁定 + 引导，或直接放行。
     * <p>{@code lock()} 内部会启动每 3s 的 title/bossbar 提醒定时器。
     */
    private void evaluateOnJoin(String player) {
        if (lockManager == null || directAuth == null || bindingService == null) return;
        if (!proxy.getPlayer(player).isPresent()) return;
        org.windy.xingtubot.common.binding.BindingService svc = bindingService;

        if (!svc.isPlayerBound(player)) {
            if (svc.hasPending(player)) {
                // 已声明 QQ（掉线/切服回来）：提示去群里发「绑定」，无需再输 QQ。
                lockManager.lock(player);
                directAuth.titlePlayer(player, "§6§l就差一步 · 请完成绑定", "§f在群里发送「绑定」完成验证");
                directAuth.messagePlayer(player, "§e你已登记 QQ，请在群里发送「§b绑定§e」完成绑定（5 分钟内有效）");
            } else {
                // 全新玩家：引导在聊天框输入 QQ 号开始白名单绑定。
                lockManager.lock(player);
                directAuth.titlePlayer(player, "§6§l欢迎 · 请绑定白名单", "§f请在聊天框输入 QQ 号开始白名单绑定");
                directAuth.messagePlayer(player, "§e欢迎！请在聊天框输入你的 §bQQ号 §e完成白名单绑定");
            }
        } else if (svc.isLoggedInSession(player)) {
            // 本会话已登录（跨子服切换）：静默解锁，不打扰群。
            lockManager.unlock(player);
        } else if (lockManager.autoLoginAllowed(player, currentIp(player))) {
            // IP 绑定的自动登录：同 IP 且在信任期内重进 → 免密自动登录。
            lockManager.unlock(player);
            directAuth.login(player);
            svc.markLoggedInSession(player);
            directAuth.titlePlayer(player, "§a§l欢迎回来", "§f同设备信任期内已自动登录");
            directAuth.messagePlayer(player, "§a✅ 同设备信任期内，已为你自动登录，祝游戏愉快！");
        } else {
            // 已绑定但需登录：锁定 + 群里推「登录」按钮卡片（fireNeedLogin 触发 onNeedLogin 回调）。
            lockManager.lock(player);
            directAuth.titlePlayer(player, "§a§l欢迎回来", "§f请在群里点机器人发的「登录」按钮");
            directAuth.messagePlayer(player, "§e欢迎回来！机器人已在群里发「§a登录§e」按钮，绑定的 QQ 点一下即可登录");
            lockManager.fireNeedLogin(player);
        }
    }

    /** 当前在线玩家的远端 IP（仅主机地址，不含端口）；取不到返回 null。 */
    private String currentIp(String player) {
        return proxy.getPlayer(player)
                .map(com.velocitypowered.api.proxy.Player::getRemoteAddress)
                .map(a -> a.getAddress() != null ? a.getAddress().getHostAddress() : null)
                .orElse(null);
    }

    /** 已绑定玩家需登录时，在目标群发带「登录」按钮的免密登录卡片。 */
    private void announceLoginButton(XingtuBotHost host, YamlBotConfig config, String playerName) {
        org.windy.xingtubot.common.module.capability.ProactiveSender sender =
                host.getService(org.windy.xingtubot.common.module.capability.ProactiveSender.class);
        if (sender == null || !sender.isReady()) return;

        java.util.List<String> groups = org.windy.xingtubot.common.util.GroupTargets
                .resolveKnownGroups(host, config.getStringList("allowed-groups"));
        if (groups.isEmpty()) return;

        final String loginWord = config.getString("login-prompt", "登录");
        final String btnLabel = LockMessages.get("login-button-label");
        final boolean showRegion = config.getBoolean("login-announce-region", true);
        final String ip = proxy.getPlayer(playerName)
                .map(com.velocitypowered.api.proxy.Player::getRemoteAddress)
                .map(addr -> addr.getAddress() != null ? addr.getAddress().getHostAddress() : null)
                .orElse(null);

        proxy.getScheduler().buildTask(this, () -> {
            String region = showRegion ? org.windy.xingtubot.auth.util.IpGeo.province(ip) : "";
            String card = buildLoginCard(playerName, region, loginWord);
            String openid = resolveOpenid(host, playerName);
            String keyboard = org.windy.xingtubot.common.util.Keyboards.callbackForUser(
                    btnLabel, loginWord, openid);
            for (String g : groups) {
                sender.sendGroupMarkdownKeyboard(g, card, keyboard);
            }
        }).schedule();
    }

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

    private static String buildLoginCard(String player, String region, String loginWord) {
        StringBuilder sb = new StringBuilder();
        sb.append(LockMessages.get("group-login-card-title"));
        sb.append(LockMessages.get("group-login-card-player")).append(player).append("\n");
        sb.append(LockMessages.format("group-login-card-tip", "{login}", loginWord));
        return sb.toString();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ExtensionBootstrap.disable(module);
    }

    private static boolean packetEventsAvailable() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            return com.github.retrooper.packetevents.PacketEvents.getAPI() != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
