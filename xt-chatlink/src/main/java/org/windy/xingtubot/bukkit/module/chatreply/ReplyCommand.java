package org.windy.xingtubot.bukkit.module.chatreply;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.service.SensitiveFilter;

/**
 * 游戏内 [点击回复] 的执行器：{@code /messagereply <群openid> <内容>}。
 *
 * <p>直接把回复<b>主动发回来源群</b>（{@link ProactiveSender}），不依赖被动回复窗口、也不再用
 * 跨 classloader 的 eventMap（旧实现把事件存在 spigot 那份、xt-chatlink 这份读不到→必失败）。
 * 群 openid 由广播时的 [点击回复] 按钮直接带过来。
 */
public class ReplyCommand implements CommandExecutor {

    // 存「取 sender 的供给器」而非 sender 本身，每次发送时现取（避免加载/注册顺序坑）。
    private volatile java.util.function.Supplier<ProactiveSender> senderSupplier;

    /** 由 {@link ChatreplyModule} 注入「供给器」（一般 {@code () -> host.getService(ProactiveSender.class)}）。 */
    public void setProactiveSender(java.util.function.Supplier<ProactiveSender> senderSupplier) {
        this.senderSupplier = senderSupplier;
    }

    /** 现取主动发送器（供给器为 null 或取出 null 都返回 null,由调用方回退队列）。 */
    private ProactiveSender sender() {
        java.util.function.Supplier<ProactiveSender> sp = this.senderSupplier;
        return sp != null ? sp.get() : null;
    }

    @Override
    public boolean onCommand(CommandSender sender0, Command command, String label, String[] args) {
        if (!(sender0 instanceof Player)) {
            sender0.sendMessage("该命令只能由玩家执行！");
            return true;
        }
        Player player = (Player) sender0;

        if (args.length < 2) {
            player.sendMessage("§e用法: /messagereply <群openid> <回复内容>（一般点 [点击回复] 自动填充）");
            return true;
        }

        String groupId = args[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            sb.append(args[i]).append(" ");
        }
        String replyContent = sb.toString().trim();
        if (replyContent.isEmpty()) {
            player.sendMessage("§c回复内容不能为空！");
            return true;
        }

        // 敏感词替换（由 core sensitive-filter.Enable 统一控制）
        SensitiveFilter sf = ChatreplyModule.getSensitiveFilter();
        if (sf != null) {
            replyContent = sf.filter(replyContent);
        }

        String outbound = player.getName() + "：" + replyContent;

        // 主动发回来源群；未就绪/失败回退被动队列
        ProactiveSender s = sender();
        if (s != null && s.isReady() && s.sendGroupMessage(groupId, outbound)) {
            player.sendMessage("§a[已回复QQ群] §f" + replyContent);
        } else {
            PendingMessageQueue.getInstance().offer(groupId, outbound);
            player.sendMessage("§e[已排队回复QQ群] §f" + replyContent + " §7（机器人就绪后或该群下次活跃时送达）");
        }
        return true;
    }
}
