package org.windy.xingtubot.bukkit.util;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI 占位符解析（compileOnly 直连，<b>不用反射</b>）。
 *
 * <p>PlaceholderAPI 是 compileOnly 软依赖：编译期有类型、运行期由服务器的 PlaceholderAPI 提供。
 * 仅在确认插件在场（{@link #isAvailable()}）后才触碰 {@link PlaceholderAPI} 类，
 * 故没装 PlaceholderAPI 时不会触发类加载、不会 NoClassDefFoundError，直接原样返回。
 *
 * <p>这是<b>核心通用工具</b>（供回复/占位符解析使用），与白名单功能无关，
 * 故归在 {@code bukkit.util}；附属插件（如 xt-auth）经 {@code compileOnly project(':spigot')}
 * 引用本类，运行期由主插件 XingtuBot 的 classloader 提供（depend: XingtuBot），不再各自复制。
 *
 * <p>须主线程调用（PAPI 多数占位符要求）。
 */
public final class PapiResolver {

    private static Boolean available; // 缓存插件可用性

    private PapiResolver() {
    }

    /** 用指定玩家上下文解析 text 中的 %xxx% 占位符；不可用/玩家离线则尽量原样返回。 */
    public static String resolve(String playerName, String text) {
        if (text == null || text.isEmpty()) return text;
        if (!isAvailable()) return text;
        try {
            Player p = playerName == null ? null : Bukkit.getPlayerExact(playerName);
            OfflinePlayer op = p != null ? p
                    : (playerName != null ? Bukkit.getOfflinePlayer(playerName) : null);
            String result = PlaceholderAPI.setPlaceholders(op, text);
            return result != null ? result : text;
        } catch (Throwable e) {
            return text;
        }
    }

    private static boolean isAvailable() {
        if (available != null) return available;
        available = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        return available;
    }
}
