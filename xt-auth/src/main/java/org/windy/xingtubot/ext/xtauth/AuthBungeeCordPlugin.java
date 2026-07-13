package org.windy.xingtubot.ext.xtauth;

import net.md_5.bungee.api.plugin.Plugin;
import org.windy.xingtubot.bungee.BungeeCordDirectAuthAdapter;
import org.windy.xingtubot.bungee.BungeeCordPlayerLock;
import org.windy.xingtubot.bungee.BungeeCordPlayerOps;
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
 *
 * <p>auth 完全自治：lockManager + DirectAuthAdapter + 进服判定，全部由本插件管理。
 * 不依赖核心 BungeeCordBridge 的任何 auth 方法。
 */
public class AuthBungeeCordPlugin extends Plugin {

    private BotModule module;
    private BungeeCordPlayerLock lockManager;

    @Override
    public void onLoad() {
        if (com.github.retrooper.packetevents.PacketEvents.getAPI() == null) {
            com.github.retrooper.packetevents.PacketEvents.setAPI(
                    io.github.retrooper.packetevents.bungee.factory.BungeePacketEventsBuilder.build(this));
            com.github.retrooper.packetevents.PacketEvents.getAPI().load();
        }
    }

    @Override
    public void onEnable() {
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
        LockMessages.load(new YamlBotConfig(getDataFolder(), getClass().getClassLoader(), "messages.yml")::getString);

        // ===== packetevents 登录锁 =====
        BungeeCordDirectAuthAdapter directAuth = null;
        if (packetEventsAvailable()) {
            lockManager = new BungeeCordPlayerLock(getProxy(), null);
            directAuth = new BungeeCordDirectAuthAdapter(lockManager, new BungeeCordPlayerOps(getProxy()));
        }

        // ===== AuthModule =====
        AuthModule authModule = new AuthModule(this, getDataFolder());
        if (directAuth != null) {
            authModule.setAuthAdapter(directAuth);
        } else {
            getLogger().warning("[Auth] packetevents 不可用，白名单解锁将不可用。");
        }
        module = ExtensionBootstrap.enable(host, authModule, config, logger, getDataFolder());

        // ===== enable() 后补全 lockManager =====
        if (lockManager != null) {
            org.windy.xingtubot.common.binding.BindingService bindingService =
                    host.getService(org.windy.xingtubot.common.binding.BindingService.class);
            if (bindingService != null) {
                lockManager.setBindingService(bindingService);
                lockManager.setOnCodeIssued(name ->
                        org.windy.xingtubot.bungee.BungeeCordJoinQrMap.giveIfEnabled(getProxy(), config, name));
                com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager()
                        .registerListener(new LockPacketListener(lockManager));
                getLogger().info("[Auth] packetevents 登录锁已启用（BungeeCord 端包级拦截）");
            } else {
                getLogger().warning("[Auth] BindingService 未就绪，packetevents 登录锁未启用。");
            }
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
