package org.windy.xingtubot.ext.xtauth;

import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.bukkit.module.whitelist.SpigotBridge;
import org.windy.xingtubot.common.config.YamlBotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ExtensionBootstrap;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.module.AuthModule;

/**
 * 白名单+登录扩展 · Bukkit 主类。
 *
 * <p>初始化 SpigotBridge（slave 模式跨服通信）+ AuthModule（绑定系统 + WhitelistHandler）。
 */
public final class AuthBukkitPlugin extends JavaPlugin {

    private BotModule module;
    private SpigotBridge spigotBridge;

    @Override
    public void onEnable() {
        XingtuBotHost host = getServer().getServicesManager().load(XingtuBotHost.class);

        BotLogger logger = new BotLogger() {
            @Override public void info(String msg) { getLogger().info(msg); }
            @Override public void warn(String msg) { getLogger().warning(msg); }
        };

        YamlBotConfig config = new YamlBotConfig(getDataFolder(), getClass().getClassLoader());

        // 部署模式（slave 手脚 / local 单机大脑）由框架统一判定，核心已写入 host.isBrain()。
        // 本扩展只读，不再自行用 ProxyDetector + whitelist-role 重复判定（那是机器人框架的职责）。
        boolean whitelistEnabled = config.getBoolean("whitelist-enable", true);
        boolean brain = host != null && host.isBrain();
        boolean slave = whitelistEnabled && !brain;
        boolean localBrain = whitelistEnabled && brain;

        // AuthModule 初始化（绑定系统 + Redis 信道）。本地模式下让 WhitelistModule 接管绑定，
        // AuthModule 跳过 store/WhitelistHandler，避免两套库 + 双重 QQ 处理。
        AuthModule authModule = new AuthModule(this, localBrain);
        module = ExtensionBootstrap.enable(host, authModule, config, logger, getDataFolder());

        if (whitelistEnabled) {
            // 获取主插件实例（XingtuBot extends JavaPlugin）
            JavaPlugin mainPlugin = (JavaPlugin) getServer().getPluginManager().getPlugin("XingtuBot");
            if (mainPlugin == null) {
                getLogger().severe("[Auth] 找不到主插件 XingtuBot，白名单无法启动");
                return;
            }
            if (slave) {
                // 手脚模式：跨服通信（PluginMessage + 可选 Redis），锁定由大脑下发驱动。
                spigotBridge = new SpigotBridge((org.windy.xingtubot.bukkit.XingtuBot) mainPlugin);
                // Redis 信道是核心通用基础设施：核心按其 config 创建并注册到服务总线，这里取用即可。
                org.windy.xingtubot.common.bridge.CrossServerChannel redis =
                        host.getService(org.windy.xingtubot.common.bridge.CrossServerChannel.class);
                if (redis != null) {
                    spigotBridge.setRedisChannel(redis);
                }
                getLogger().info("[Auth] 白名单手脚模式(slave)已启用");
            } else {
                // 本地模式（单机即大脑）：WhitelistModule 接管 进服锁定 + 绑定 + 群验证 + 定时提醒
                org.windy.xingtubot.bukkit.module.whitelist.WhitelistModule wl =
                        new org.windy.xingtubot.bukkit.module.whitelist.WhitelistModule(
                                (org.windy.xingtubot.bukkit.XingtuBot) mainPlugin);
                // 把真实绑定库注册到服务总线，供其他扩展（如 xt-group 自定义命令）查玩家数据
                host.registerService(
                        org.windy.xingtubot.common.binding.BindingRepository.class, wl.getBindingStore());
                host.registerService(
                        org.windy.xingtubot.common.binding.BindingService.class, wl.getService());
                getLogger().info("[Auth] 自研锁 + 绑定 + 群验证）");
            }
        }
    }

    @Override
    public void onDisable() {
        ExtensionBootstrap.disable(module);
    }
}
