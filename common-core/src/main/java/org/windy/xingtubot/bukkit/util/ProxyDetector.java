package org.windy.xingtubot.bukkit.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * 探测本子服是否挂在代理（BungeeCord / Velocity）后面。
 *
 * <p>这是<b>核心部署判定</b>（决定本端 bot 是否本地运行还是由 Velocity 统一接管），
 * 与白名单功能无关，故归在 {@code bukkit.util}；附属插件（如 xt-auth）经
 * {@code compileOnly project(':spigot')} 引用本类，运行期由主插件 XingtuBot 的 classloader 提供，不再各自复制。
 *
 * <p>不依赖插件消息握手（启动期无玩家连接，握手发不出去），改为直接读服务器
 * 自身的代理开关配置。任一为真即视为挂在代理后：
 * <ul>
 *   <li>{@code spigot.yml} → {@code settings.bungeecord}（BungeeCord / 旧式 Velocity 转发）</li>
 *   <li>{@code config/paper-global.yml} → {@code proxies.velocity.enabled} 或
 *       {@code proxies.bungee-cord.enabled}（现代 Paper）</li>
 *   <li>旧版 {@code paper.yml} → {@code settings.velocity-support.enabled}（兜底）</li>
 * </ul>
 * 读取失败一律按独立服处理（返回 false）。
 */
public final class ProxyDetector {

    private ProxyDetector() {
    }

    /** @return 本子服是否挂在代理后面。 */
    public static boolean isBehindProxy(Plugin plugin) {

        File root = new File(".");

        if (readBool(new File(root, "spigot.yml"), "settings.bungeecord")) {
            return true;
        }
        File paperGlobal = new File(root, "config/paper-global.yml");
        if (readBool(paperGlobal, "proxies.velocity.enabled")
                || readBool(paperGlobal, "proxies.bungee-cord.enabled")) {
            return true;
        }

        if (readBool(new File(root, "paper.yml"), "settings.velocity-support.enabled")) {
            return true;
        }
        return false;
    }

    private static boolean readBool(File file, String path) {
        if (file == null || !file.isFile()) return false;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            return yaml.getBoolean(path, false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
