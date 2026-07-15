package org.windy.xingtubot.bukkit.module.chatreply.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.windy.xingtubot.bukkit.module.chatreply.ChatreplyModule;
import org.windy.xingtubot.common.lock.PlayerLockManager;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.service.SensitiveFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 游戏内聊天 → QQ 群转发（Bukkit 侧，对标 Velocity 的 GroupChatLink.onPlayerChat）。
 *
 * <p>监听 {@link AsyncPlayerChatEvent}，把玩家聊天<b>广播到 allowed-groups 里配置的每个具体群</b>。
 * 群服互联必须绑定具体群才工作——不再靠"最近谁说话"猜测目标群（多群会乱跳、不可控）。
 * allowed-groups 为 {@code *} 或留空（没有具体群）时，game→QQ 不主动推送。
 *
 * <p>每个目标群：尽力先走 {@link QqOpenApiClient} 主动消息，失败回退被动队列。
 * 注册态（{@link PlayerLockManager} 锁定）的玩家消息<b>不会</b>转发。
 */
public class GameChatForwarder implements Listener {

    private final PlayerLockManager lockState;
    // 主动消息发送器：存「取 sender 的供给器」而非 sender 本身，每次发送时现取。
    // 避免在本插件 onEnable 那一刻核心还没 registerService(ProactiveSender) → 缓存了 null 且永不自愈。
    private volatile java.util.function.Supplier<ProactiveSender> senderSupplier;
    // 群服互联目标群（复用 allowed-groups）：空/含"*"=未指定具体群。
    private volatile Set<String> allowedGroups = Collections.singleton("*");
    // game→QQ 聊天行 markdown 模板（占位符 {player}/{message}）。
    private volatile String gameFormat = org.windy.xingtubot.chatlink.util.ChatlinkFormat.DEFAULT;

    public GameChatForwarder(PlayerLockManager lockState) {
        this.lockState = lockState;
    }

    /**
     * 设置「主动消息发送器供给器」（一般传 {@code () -> host.getService(ProactiveSender.class)}）。
     * 每次发送时现取,故不受插件加载/注册顺序影响。
     */
    public void setProactiveSender(java.util.function.Supplier<ProactiveSender> senderSupplier) {
        this.senderSupplier = senderSupplier;
    }

    /** 现取主动发送器（供给器为 null 或取出 null 都返回 null,由调用方回退队列）。 */
    private ProactiveSender sender() {
        java.util.function.Supplier<ProactiveSender> sp = this.senderSupplier;
        return sp != null ? sp.get() : null;
    }

    /** 设置群服互联目标群（来自 config 的 allowed-groups）。空/含"*"=未指定具体群。 */
    public void setAllowedGroups(List<String> groups) {
        this.allowedGroups = (groups == null || groups.isEmpty())
                ? Collections.singleton("*") : new HashSet<>(groups);
    }

    /** 设置 game→QQ 聊天行 markdown 模板（占位符 {player}/{message}；空/null 用默认）。 */
    public void setGameFormat(String format) {
        this.gameFormat = (format == null || format.trim().isEmpty())
                ? org.windy.xingtubot.chatlink.util.ChatlinkFormat.DEFAULT : format;
    }

    /** game→QQ 的目标群：allowed-groups 里的全部具体群（排除 "*"）。无具体群时返回空。 */
    private List<String> bridgeTargets() {
        List<String> out = new ArrayList<>();
        for (String g : allowedGroups) {
            if (g != null && !"*".equals(g) && !g.trim().isEmpty()) out.add(g);
        }
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // 注册态玩家不转发
        Player player = event.getPlayer();
        if (lockState != null && lockState.isLocked(player.getName())) {
            return;
        }

        List<String> targets = bridgeTargets();
        if (targets.isEmpty()) {
            return; // 没配具体群 → 不主动推（群服互联需把 allowed-groups 配成具体群）
        }

        String message = event.getMessage();
        // 游戏→QQ：玩家发言进群前过滤敏感词（群服互联开关）
        ChatreplyModule cm = ChatreplyModule.getInstance();
        if (cm != null && cm.getConfig().getBoolean("sensitive-filter-chatlink", true)) {
            SensitiveFilter filter = ChatreplyModule.getSensitiveFilter();
            if (filter != null) {
                message = filter.filter(message);
            }
        }
        // 主动推送走 markdown（加粗名等修饰生效）；队列兜底走纯文本（被动通道会再统一转义）
        final String md = org.windy.xingtubot.chatlink.util.ChatlinkFormat.markdown(gameFormat, player.getName(), message);
        final String plain = org.windy.xingtubot.chatlink.util.ChatlinkFormat.plain(gameFormat, player.getName(), message);

        // 广播到每个具体群：尽力先主动消息，失败回退被动队列
        final ProactiveSender s = sender();
        for (String gid : targets) {
            final String g = gid;
            CompletableFuture.runAsync(() -> {
                if (s != null && s.isReady() && s.sendGroupMarkdown(g, md)) {
                    return;
                }
                PendingMessageQueue.getInstance().offer(g, plain);
            });
        }
    }
}
