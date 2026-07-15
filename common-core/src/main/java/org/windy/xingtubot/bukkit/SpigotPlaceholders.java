package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.reply.PlaceholderResolver;
import org.windy.xingtubot.bukkit.util.PapiResolver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Spigot 端占位符解析。
 * 内置占位符同步替换 + PAPI %xxx% 通过反射解析。
 *
 * <p>绑定数据通过服务总线反射获取（xt-auth 提供 BindingRepository 实现）。
 */
public class SpigotPlaceholders implements PlaceholderResolver {

    private final Object bindingStore; // xt-auth BindingRepository，运行时反射访问
    private final String defaultSender;

    public SpigotPlaceholders(Object bindingStore, String defaultSender) {
        this.bindingStore = bindingStore;
        this.defaultSender = defaultSender != null ? defaultSender : "群成员";
    }

    @Override
    public void resolve(String text, BotMessageContext event, java.util.function.Consumer<String> callback) {
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

    /** 查询发送者绑定的玩家名（反射调用 bindingStore.getPlayersByOpenid）。 */
    @SuppressWarnings("unchecked")
    private String boundPlayer(BotMessageContext event) {
        if (event.getSenderId() == null || bindingStore == null) return null;
        try {
            List<String> players = (List<String>) bindingStore.getClass()
                    .getMethod("getPlayersByOpenid", String.class)
                    .invoke(bindingStore, event.getSenderId());
            return (players != null && !players.isEmpty()) ? players.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内置占位符 ====================

    private String resolveBuiltin(String text, BotMessageContext event) {
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

    private String senderName(BotMessageContext event) {
        if (event.getSenderId() != null) {
            String player = boundPlayer(event);
            if (player != null) return player;
        }
        if (event.getUsername() != null && !event.getUsername().isEmpty()) {
            return event.getUsername();
        }
        return defaultSender;
    }

    // ==================== PAPI Expansion 注册 ====================

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
                        try {
                            Object entry = bindingStore.getClass()
                                    .getMethod("findByPlayer", String.class)
                                    .invoke(bindingStore, player.getName());
                            return entry != null ? "true" : "false";
                        } catch (Exception e) {
                            return "false";
                        }
                    }
                    return "false";
                }
                case "player": {
                    if (bindingStore != null) {
                        try {
                            Object entry = bindingStore.getClass()
                                    .getMethod("findByPlayer", String.class)
                                    .invoke(bindingStore, player.getName());
                            if (entry != null) {
                                return (String) entry.getClass().getField("player").get(entry);
                            }
                        } catch (Exception e) {
                            // fall through
                        }
                    }
                    return "";
                }
                case "bot_name":
                    return org.windy.xingtubot.common.runtime.BotRuntimeState.getBotName();
                default:
                    return null;
            }
        }
    }
}
