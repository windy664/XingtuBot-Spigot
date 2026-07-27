package org.windy.xingtubot.bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.windy.xingtubot.common.qq.QqOpenApiClient;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.util.Md;

import java.util.Collections;
import java.util.List;

/**
 * 游戏内向 QQ 群发消息：/qq <消息>
 * 需要权限节点 xingtubot.qq.send。
 *
 * <p>有 apiClient 时走主动消息（即时推送），否则回退到被动队列。
 */
public class QQSendCommand implements CommandExecutor, TabCompleter {

    private final String permission;
    private final PendingMessageQueue queue;
    private volatile QqOpenApiClient apiClient;
    private volatile String defaultGroupOpenid; // 最近活跃的群 openid

    public QQSendCommand(String permission) {
        this.permission = permission;
        this.queue = PendingMessageQueue.getInstance();
    }

    /** 设置 OpenAPI 客户端，启用主动消息推送。 */
    public void setApiClient(QqOpenApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /** 设置默认目标群 openid（从最近的群消息事件中获取）。 */
    public void setDefaultGroupOpenid(String groupOpenid) {
        this.defaultGroupOpenid = groupOpenid;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e用法: /qq <消息内容>");
            sender.sendMessage("§7向 QQ 群发送消息，支持 markdown 格式（**粗体** *斜体* `代码`）");
            return true;
        }

        // 权限检查
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("§c你没有权限使用此命令（需要 " + permission + "）");
            return true;
        }

        // 构造消息
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(args[i]);
        }
        String content = sb.toString().trim();
        if (content.isEmpty()) {
            sender.sendMessage("§c消息内容不能为空");
            return true;
        }

        // 发送者名称
        String senderName;
        if (sender instanceof Player) {
            senderName = ((Player) sender).getDisplayName();
        } else {
            senderName = "控制台";
        }

        // 标题用纯文本，正文保留 markdown 格式
        String header = "📢 \\[" + Md.plain(senderName) + "\\] ";
        String markdown = header + content;

        // 优先走主动消息（markdown 通道）
        QqOpenApiClient api = this.apiClient;
        String gid = this.defaultGroupOpenid;
        if (api != null && gid != null) {
            try {
                api.sendProactiveGroupMarkdown(gid, markdown);
                sender.sendMessage("§a✅ 已发送到 QQ 群: " + content);
                return true;
            } catch (Exception e) {
                // 主动失败 → 回退被动队列
                sender.sendMessage("§e⚠️ 主动推送失败，已加入被动队列: " + e.getMessage());
            }
        }

        // 被动模式：有目标群按群定向入队，否则进全局队列（队列走纯文本，避免markdown残留）
        String plainMessage = "📢 [" + senderName + "] " + content;
        if (gid != null) {
            queue.offer(gid, plainMessage);
        } else {
            queue.offer(plainMessage);
        }
        sender.sendMessage("§a✅ 已加入推送队列，下次群里有人 @机器人 时一起发出");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
