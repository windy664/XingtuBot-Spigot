package org.windy.xingtubot.module.chatreply;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.windy.xingtubot.event.GuildMessageEvent;


import java.util.HashMap;
import java.util.Map;

public class ReplyCommand implements CommandExecutor {

    // 用来暂存 formId 和原始事件的映射，方便回复时用
    // 这个map需要在收到GuildMessageEvent时更新
    public static final Map<String, GuildMessageEvent> eventMap = new HashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("该命令只能由玩家执行！");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("用法: /messagereply <formId> <回复内容>");
            return true;
        }

        String formId = args[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            sb.append(args[i]).append(" ");
        }
        String replyContent = sb.toString().trim();

        if (replyContent.isEmpty()) {
            sender.sendMessage("回复内容不能为空！");
            return true;
        }

        GuildMessageEvent originalEvent = eventMap.get(formId);
        if (originalEvent == null) {
            sender.sendMessage("找不到对应的消息，回复失败！");
            return true;
        }

        // 敏感词替换
        SensitiveFilter filter = ChatreplyModule.getSensitiveFilter();
        if (filter != null) {
            replyContent = filter.filter(replyContent);
        }

        originalEvent.reply("<" + ((Player) sender).getDisplayName() + "> " + replyContent);
        ((Player) sender).chat("§2@QQ群 §f" + replyContent);
        return true;
    }
}