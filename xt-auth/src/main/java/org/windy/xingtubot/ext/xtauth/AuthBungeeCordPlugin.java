package org.windy.xingtubot.ext.xtauth;

import net.md_5.bungee.api.plugin.Plugin;
import org.windy.xingtubot.common.config.YamlBotConfig;
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

    @Override
    public void onEnable() {
        XingtuBotHost host = findHost();
        if (host == null) { getLogger().severe("[Auth] 找不到主插件 XingtuBotBungeeCord"); return; }

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        // 取核心 BungeeCordBridge：它持有认证适配器（DO_LOGIN/DO_REGISTER 下发通道），
        // 须在 enable 之前注入 AuthModule，否则大脑侧 BindingService 的 auth 为 null，
        // 群里「登录」/「绑定」命中后无法驱动子服解锁。
        net.md_5.bungee.api.plugin.Plugin main = getProxy().getPluginManager().getPlugin("XingtuBotBungeeCord");
        org.windy.xingtubot.bungee.BungeeCordBridge bridge = null;
        if (main instanceof org.windy.xingtubot.bungee.XingtuBotBungeeCord) {
            bridge = ((org.windy.xingtubot.bungee.XingtuBotBungeeCord) main).getBridge();
        }

        AuthModule authModule = new AuthModule(this, getDataFolder());
        if (bridge != null) {
            authModule.setAuthAdapter(bridge.getAuthAdapter());
        } else {
            getLogger().warning("[Auth] 未找到核心 BungeeCordBridge（server-role=off？）：白名单解锁将不可用。");
        }
        module = ExtensionBootstrap.enable(host, authModule, config, logger, getDataFolder());

        // 注册「未绑定进服 → 加群二维码」回调到核心 BungeeCordBridge（白名单 QR 完整归属 xt-auth，
        // 群号/链接读 xt-auth 自己的 config）。
        if (bridge != null) {
            bridge.setOnUnboundJoin(name ->
                    org.windy.xingtubot.bungee.BungeeCordJoinQrMap.giveIfEnabled(getProxy(), config, name));
            // 跨服 Redis 信道由核心创建并注入到 bridge（通用基础设施，配置在核心 config），此处不再处理。
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
}
