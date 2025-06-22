package org.windy.xingtubot.bukkit.module.chatreply.listener;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.windy.xingtubot.bukkit.XingtuBot;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.bukkit.module.chatreply.ChatreplyModule;
import org.windy.xingtubot.bukkit.module.chatreply.ReplyCommand;
//import org.windy.xingtubot.bukkit.module.whitelist.WhitelistModule;
import org.windy.xingtubot.bukkit.module.whitelist.api.WhiteListAPI;

import java.util.List;

public class GuildMessageListener implements Listener {

    @EventHandler
    public void onGuildMessage(GuildMessageEvent event) {
        String message = event.getMessage().trim();
        String formId = event.getFormId();

        // 保存事件，方便 reply 命令使用
        ReplyCommand.eventMap.put(formId, event);

        String prefix = ChatreplyModule.getInstance()
                .plugin
                .getConfig()
                .getString("startsWith", "say");

        if (!message.startsWith(prefix)) return;

        String content = message.substring(prefix.length()).trim();
        if (content.isEmpty()) {
            event.reply("发送内容不能为空！");
            return;
        }

// 修改后的正确写法
        List<String> players = WhiteListAPI.getPlayersByFormId(formId);
        String senderName;
        if (players.isEmpty()) {
            senderName = ChatreplyModule.getInstance().plugin.getConfig().getString("entries-Empty", "群成员");
            XingtuBot.getInstance().log("[ChatReply] 来自未绑定白名单用户的消息：FormId=" + formId + "，将使用默认昵称：" + senderName);
        } else {
            senderName = players.get(0); // ✅ 因为players是List<String>
        }

        // 构建主消息部分
        String chatPrefix = ChatreplyModule.getInstance().plugin.getConfig()
                .getString("chat-format", "🐧§cQQ群 §7| §f");

        TextComponent chatMessage = new TextComponent(chatPrefix);

        TextComponent sender = new TextComponent(senderName + "💬：");
        sender.setColor(ChatColor.WHITE);
        chatMessage.addExtra(sender);

        TextComponent contentComp = new TextComponent(content);
        contentComp.setColor(ChatColor.WHITE);
        chatMessage.addExtra(contentComp);

        // 构建“点击回复”按钮
        TextComponent clickReply = new TextComponent(" [点击回复]");
        clickReply.setColor(ChatColor.YELLOW);
        clickReply.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/messagereply " + formId + " "));
        clickReply.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击回复群聊信息").color(ChatColor.GRAY).create()));

        // 广播到在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.spigot().sendMessage(chatMessage, clickReply);
        }
    }
}
