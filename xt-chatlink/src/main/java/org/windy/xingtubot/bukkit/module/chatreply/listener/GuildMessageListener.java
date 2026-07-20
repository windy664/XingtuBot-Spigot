package org.windy.xingtubot.bukkit.module.chatreply.listener;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.service.SensitiveFilter;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * QQ 群 → 游戏 的广播实现（Spigot 侧）。
 *
 * <p>本类只负责"怎么显示"：解析 @提及、解析发送者昵称、拼 [点击回复] 按钮、发给全体在线玩家。
 * 所有依赖通过参数注入，不直接引用主插件或其它附属的类。
 */
public final class GuildMessageListener {

    private GuildMessageListener() {
    }

    /**
     * 把一条群消息广播给所有在线玩家（自动切回主线程执行）。
     *
     * @param plugin          插件实例（用于调度回主线程 + 读配置）
     * @param formId          发送者标识（openid），用于解析昵称
     * @param username        QQ 昵称（可空）
     * @param rawContent      已剥掉前缀、已解析 @提及的正文
     * @param groupId         来源群 openid，用于 [点击回复] 主动回该群（可空则不显示回复按钮）
     * @param sensitiveFilter 敏感词过滤器（可空）
     * @param playerLookup    通过 formId 查绑定玩家名的函数（可空，返回空列表表示未绑定）
     */
    public static void broadcastToGame(JavaPlugin plugin, String formId, String username,
                                       String rawContent, String groupId,
                                       SensitiveFilter sensitiveFilter,
                                       Function<String, List<String>> playerLookup) {
        if (rawContent == null || rawContent.isEmpty()) return;

        // 解析消息里的 @提及：<@openid> → @昵称
        String resolved = OpenidNameCache.getInstance().resolveMentions(rawContent);
        // QQ→游戏：群消息进游戏前过滤敏感词
        if (sensitiveFilter != null && plugin.getConfig().getBoolean("sensitive-filter-chatlink", true)) {
            resolved = sensitiveFilter.filter(resolved);
        }
        final String content = resolved;
        if (content.isEmpty()) return;

        // 解析发送者昵称：优先绑定的玩家名，其次 QQ 昵称
        List<String> players = playerLookup != null
                ? playerLookup.apply(formId)
                : Collections.emptyList();

        final String senderName = !players.isEmpty() ? players.get(0) : username;

        String chatPrefix = plugin.getConfig().getString("chat-format", "🐧§cQQ群 §7| §f");

        TextComponent chatMessage = new TextComponent(chatPrefix);
        TextComponent sender = new TextComponent(senderName + "💬：");
        sender.setColor(ChatColor.WHITE);
        chatMessage.addExtra(sender);

        TextComponent contentComp = new TextComponent(content);
        contentComp.setColor(ChatColor.WHITE);
        chatMessage.addExtra(contentComp);

        // [点击回复]：把回复主动发回来源群（需 group openid）。无 groupId 则不显示按钮。
        final TextComponent clickReply;
        if (groupId != null && !groupId.isEmpty()) {
            clickReply = new TextComponent(" [点击回复]");
            clickReply.setColor(ChatColor.YELLOW);
            clickReply.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                    "/messagereply " + groupId + " "));
            clickReply.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("点击把回复发回该 QQ 群").color(ChatColor.GRAY).create()));
        } else {
            clickReply = null;
        }

        // 广播回调可能在异步线程触发（registry 工作线程池），切回主线程再发
        Runnable broadcast = () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (clickReply != null) {
                    player.spigot().sendMessage(chatMessage, clickReply);
                } else {
                    player.spigot().sendMessage(chatMessage);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            broadcast.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, broadcast);
        }
    }
}
