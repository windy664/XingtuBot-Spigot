package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.windy.xingtubot.common.binding.BindingEntry;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.reply.PlaceholderResolver;
import org.windy.xingtubot.bukkit.util.PapiResolver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Spigot 端占位符解析。
 * 内置占位符同步替换 + PAPI %xxx% 通过反射解析。
 *
 * <p>绑定数据直接读 BindingRepository（由 xt-auth 注册到服务总线）。
 *
 * <p>内置占位符：
 * <ul>
 *   <li>{online} 当前服务器在线人数</li>
 *   <li>{sender} 发送者（已绑定显示玩家名，否则 QQ 昵称）</li>
 *   <li>{date} 日期 {time} 时间</li>
 *   <li>{max} 最大在线人数</li>
 *   <li>{player_names} 在线玩家名列表</li>
 * </ul>
 *
 * <p>PAPI 变量（%xingtubot_*%）：
 * <ul>
 *   <li>%xingtubot_bound% 当前玩家是否已绑定（true/false）</li>
 *   <li>%xingtubot_player% 绑定的玩家名（未绑定返回空）</li>
 *   <li>%xingtubot_bot_name% 机器人昵称</li>
 * </ul>
 */
public class SpigotPlaceholders implements PlaceholderResolver {

    private final BindingRepository bindingStore;
    private final String defaultSender;

    public SpigotPlaceholders(BindingRepository bindingStore, String defaultSender) {
        this.bindingStore = bindingStore;
        this.defaultSender = defaultSender != null ? defaultSender : "群成员";
    }

    @Override
    public void resolve(String text, BotMessageEvent event, java.util.function.Consumer<String> callback) {
        if (text == null || text.isEmpty()) {
            callback.accept(text);
            return;
        }

        // ① 内置占位符同步替换
        String result = resolveBuiltin(text, event);

        // ② PAPI %xxx% 解析（有玩家上下文时）
        if (result.contains("%")) {
            String playerName = boundPlayer(event);
            if (playerName != null) {
                result = PapiResolver.resolve(playerName, result);
            }
        }

        callback.accept(result);
    }

    /** 查询发送者绑定的玩家名（直接查 bindingStore，未绑定返回 null）。 */
    private String boundPlayer(BotMessageEvent event) {
        if (event.getFormId() == null || bindingStore == null) return null;
        List<String> players = bindingStore.getPlayersByOpenid(event.getFormId());
        return players.isEmpty() ? null : players.get(0);
    }

    // ==================== 内置占位符 ====================

    private String resolveBuiltin(String text, BotMessageEvent event) {
        String r = text;

        if (r.contains("{online}")) {
            r = r.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
        }
        if (r.contains("{max}")) {
            r = r.replace("{max}", String.valueOf(Bukkit.getMaxPlayers()));
        }
        if (r.contains("{sender}")) {
            r = r.replace("{sender}", senderName(event));
        }
        if (r.contains("{date}")) {
            r = r.replace("{date}", new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        }
        if (r.contains("{time}")) {
            r = r.replace("{time}", new SimpleDateFormat("HH:mm").format(new Date()));
        }
        if (r.contains("{player_names}")) {
            StringBuilder sb = new StringBuilder();
            Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
            for (int i = 0; i < players.length; i++) {
                if (i > 0) sb.append("、");
                sb.append(players[i].getName());
            }
            r = r.replace("{player_names}", sb.length() > 0 ? sb.toString() : "暂无");
        }

        return r;
    }

    private String senderName(BotMessageEvent event) {
        if (event.getFormId() != null) {
            if (bindingStore != null) {
                List<String> players = bindingStore.getPlayersByOpenid(event.getFormId());
                if (!players.isEmpty()) return players.get(0);
            }
        }
        if (event.getUsername() != null && !event.getUsername().isEmpty()) {
            return event.getUsername();
        }
        return defaultSender;
    }

    // ==================== PAPI Expansion 注册 ====================

    /**
     * 注册 %xingtubot_*% PAPI 变量（如果 PAPI 已安装）。
     *
     * <p>注册的变量：
     * <ul>
     *   <li>%xingtubot_bound%    — 当前玩家是否已绑定（true/false）</li>
     *   <li>%xingtubot_player%   — 绑定的玩家名（未绑定返回空）</li>
     *   <li>%xingtubot_bot_name% — 机器人昵称</li>
     * </ul>
     */
    public void registerPapiExpansion() {
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
            new XingtuBotExpansion().register();
        } catch (Exception e) {
            // PAPI 不可用，静默忽略
        }
    }

    private class XingtuBotExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {

        @Override public String getIdentifier() { return "xingtubot"; }
        @Override public String getAuthor() { return "windy"; }
        @Override public String getVersion() { return "1.0"; }
        @Override public boolean persist() { return true; }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null || params == null) return "";
            switch (params.toLowerCase()) {
                case "bound": {
                    if (bindingStore != null) {
                        return bindingStore.findByPlayer(player.getName()) != null ? "true" : "false";
                    }
                    return "false";
                }
                case "player": {
                    if (bindingStore != null) {
                        BindingEntry entry =
                                bindingStore.findByPlayer(player.getName());
                        return entry != null ? entry.player : "";
                    }
                    return "";
                }
                case "bot_name":
                    return org.windy.xingtubot.common.BotIdentity.getName();
                default:
                    return null;
            }
        }
    }
}
