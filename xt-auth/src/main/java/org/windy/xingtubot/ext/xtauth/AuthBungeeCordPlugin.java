package org.windy.xingtubot.ext.xtauth;

import net.md_5.bungee.api.plugin.Plugin;
import org.windy.xingtubot.bungee.BungeeCordDirectAuthAdapter;
import org.windy.xingtubot.bungee.BungeeCordPlayerLock;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.whitelist.LockMessages;
import org.windy.xingtubot.common.whitelist.LockPacketListener;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.module.XingtuBotHostProvider;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.AuthModule;

/**
 * 白名单+登录扩展 · BungeeCord 主类。
 */
public class AuthBungeeCordPlugin extends Plugin {

    private BotModule module;
    private BungeeCordPlayerLock lockManager;

    @Override
    public void onLoad() {
        // packetevents 内置进本 jar：自行初始化（不再依赖外部 packetevents 插件）；已初始化则跳过。
        if (com.github.retrooper.packetevents.PacketEvents.getAPI() == null) {
            com.github.retrooper.packetevents.PacketEvents.setAPI(
                    io.github.retrooper.packetevents.bungee.factory.BungeePacketEventsBuilder.build(this));
            com.github.retrooper.packetevents.PacketEvents.getAPI().load();
        }
    }

    @Override
    public void onEnable() {
        // packetevents 内置：使用前完成 init()（load() 已在 onLoad 执行）。
        if (com.github.retrooper.packetevents.PacketEvents.getAPI() != null
                && !com.github.retrooper.packetevents.PacketEvents.getAPI().isInitialized()) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().init();
        }
        XingtuBotHost host = findHost();
        if (host == null) { getLogger().severe("[Auth] 找不到主插件 XingtuBotBungeeCord"); return; }

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        // 游戏内锁定文案：从独立 messages.yml 覆盖默认（首启自动释放）
        LockMessages.load(new YamlBotConfig(getDataFolder(), getClass().getClassLoader(), "messages.yml")::getString);

        // 取核心 BungeeCordBridge
        net.md_5.bungee.api.plugin.Plugin main = getProxy().getPluginManager().getPlugin("XingtuBotBungeeCord");
        org.windy.xingtubot.bungee.BungeeCordBridge bridge = null;
        if (main instanceof org.windy.xingtubot.bungee.XingtuBotBungeeCord) {
            bridge = ((org.windy.xingtubot.bungee.XingtuBotBungeeCord) main).getBridge();
        }

        // ===== packetevents 登录锁预初始化 =====
        BungeeCordDirectAuthAdapter directAuth = null;
        if (bridge != null && packetEventsAvailable()) {
            lockManager = new BungeeCordPlayerLock(getProxy(), this, null);
            directAuth = new BungeeCordDirectAuthAdapter(getProxy(), lockManager);
            bridge.setLockManager(lockManager);
        }

        AuthModule authModule = new AuthModule(this, getDataFolder());
        if (directAuth != null) {
            authModule.setAuthAdapter(directAuth);
        } else if (bridge != null) {
            authModule.setAuthAdapter(bridge.getAuthAdapter());
        } else {
            getLogger().warning("[Auth] 未找到核心 BungeeCordBridge（server-role=off？）：白名单解锁将不可用。");
        }
        module = ExtensionBootstrap.enable(host, authModule, config, logger, getDataFolder());

        // ===== packetevents 登录锁补全 =====
        if (lockManager != null) {
            org.windy.xingtubot.common.binding.BindingService bindingService =
                    host != null ? host.getService(org.windy.xingtubot.common.binding.BindingService.class) : null;
            if (bindingService != null) {
                lockManager.setBindingService(bindingService);

                // QQ 登记成功后发加群二维码地图
                lockManager.setOnCodeIssued(name ->
                        org.windy.xingtubot.bungee.BungeeCordJoinQrMap.giveIfEnabled(getProxy(), config, name));

                com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                        .registerListener(new LockPacketListener(lockManager));
                getLogger().info("[Auth] packetevents 登录锁已启用（BungeeCord 端包级拦截）");
            } else {
                getLogger().warning("[Auth] BindingService 未就绪，packetevents 登录锁未启用。");
            }
        }

        // 注册「未绑定进服 → 加群二维码」回调
        if (bridge != null) {
            bridge.setOnUnboundJoin(name ->
                    org.windy.xingtubot.bungee.BungeeCordJoinQrMap.giveIfEnabled(getProxy(), config, name));
        }
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
    }

    private XingtuBotHost findHost() {
        net.md_5.bungee.api.plugin.Plugin main = getProxy().getPluginManager().getPlugin("XingtuBotBungeeCord");
        if (main instanceof XingtuBotHostProvider) return ((XingtuBotHostProvider) main).getHost();
        return null;
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
