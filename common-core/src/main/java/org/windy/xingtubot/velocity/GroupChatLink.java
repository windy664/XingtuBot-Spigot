package org.windy.xingtubot.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.queue.PendingMessageQueue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 群服互联（纯 Velocity 层，VC 群组用）。
 *
 * <p>无需跨服 plugin message——Velocity 在代理层即可：
 * <ul>
 *   <li>群 → 服：{@link #onGroupMessage} 把群消息广播给所有在线玩家；</li>
 *   <li>服 → 群：监听 {@link PlayerChatEvent}，玩家打字就发回群。
 *       经 {@link ProactiveSender} 能力走主动消息（即时推送），未就绪则挂靠被动回复额度。</li>
 * </ul>
 *
 * <p><b>主动通道用核心的惰性能力 {@link ProactiveSender}，而非裸 apiClient</b>：
 * 经 {@link #setSender(java.util.function.Supplier)} 注入「供给器」，<b>每次发送时现取</b> service，
 * 故 bot 何时连上都自动跟随——避免「本扩展 onEnable 时核心还没注册 → 一次性缓存了 null 且永不自愈」的加载顺序坑。
 */
public class GroupChatLink {

    private final ProxyServer proxy;
    private final String chatFormat;        // 群消息在游戏内的前缀
    // game→QQ 聊天行 markdown 模板（占位符 {player}/{message}）。
    private volatile String gameFormat = org.windy.xingtubot.common.util.ChatlinkFormat.DEFAULT;
    // 主动消息能力：存「取 sender 的供给器」而非 sender 本身，每次发送时现取。
    // 避免在本插件 onEnable 那一刻核心还没 registerService(ProactiveSender) → 缓存了 null 且永不自愈。
    private volatile java.util.function.Supplier<ProactiveSender> senderSupplier;
    private volatile String lastGroupOpenid;      // 最近活跃的群 openid（供主动推送用）
    // 群服互联白名单（复用 allowed-groups）：空/含"*"=全部群。仅约束 game→QQ 推送目标。
    private volatile Set<String> allowedGroups = Collections.singleton("*");
    // 敏感词过滤器（仅群服互联用，可 null=不过滤）。
    private volatile org.windy.xingtubot.common.service.SensitiveFilter sensitiveFilter;
    // 调试日志（可 null）。仅 DebugFlag 开启时输出，供排查群服互联链路用。
    private volatile BotLogger logger;

    // 最近一条群消息事件 + 到期时间（用于服→群挂靠回复额度，被动模式兜底）
    private final AtomicReference<Holder> lastGroupMsg = new AtomicReference<>();

    private static final class Holder {
        final BotMessageEvent event;
        final long expireAt;
        Holder(BotMessageEvent event) {
            this.event = event;
            this.expireAt = System.currentTimeMillis() + 5 * 60 * 1000;
        }
    }

    public GroupChatLink(ProxyServer proxy, Object plugin, String chatFormat) {
        this.proxy = proxy;
        this.chatFormat = chatFormat == null ? "§b[QQ群] §f" : chatFormat;
        proxy.getEventManager().register(plugin, this);
    }

    /**
     * 注入「主动消息能力供给器」（一般传 {@code () -> host.getService(ProactiveSender.class)}）。
     * 每次发送时现取,故不受插件加载/注册顺序影响。
     */
    public void setSender(java.util.function.Supplier<ProactiveSender> senderSupplier) {
        this.senderSupplier = senderSupplier;
    }

    /** 现取主动发送器（供给器为 null 或取出 null 都返回 null,由调用方回退队列）。 */
    private ProactiveSender sender() {
        java.util.function.Supplier<ProactiveSender> sp = this.senderSupplier;
        return sp != null ? sp.get() : null;
    }

    /** 设置敏感词过滤器（群服互联双向过滤；传 null 关闭）。 */
    public void setSensitiveFilter(org.windy.xingtubot.common.service.SensitiveFilter filter) {
        this.sensitiveFilter = filter;
    }

    /** 设置 game→QQ 聊天行 markdown 模板（占位符 {player}/{message}；空/null 用默认）。 */
    public void setGameFormat(String format) {
        this.gameFormat = (format == null || format.trim().isEmpty())
                ? org.windy.xingtubot.common.util.ChatlinkFormat.DEFAULT : format;
    }

    /** 注入调试日志器（仅 DebugFlag 开启时打点；传 null 关闭）。 */
    public void setLogger(BotLogger logger) {
        this.logger = logger;
    }

    /** 调试日志：有 logger 且 DebugFlag 开启才输出（实时跟随 /xtb debug）。 */
    private void debug(String msg) {
        BotLogger l = this.logger;
        if (l != null && org.windy.xingtubot.common.api.DebugFlag.isOn()) l.info(msg);
    }

    /** 群服互联文本过滤：有 filter 才过，否则原样。 */
    private String filterChatlink(String text) {
        org.windy.xingtubot.common.service.SensitiveFilter f = this.sensitiveFilter;
        return f != null ? f.filter(text) : text;
    }

    /** 设置群服互联白名单（来自 config 的 allowed-groups）。空/含"*"=全部群。 */
    public void setAllowedGroups(List<String> groups) {
        this.allowedGroups = (groups == null || groups.isEmpty())
                ? Collections.singleton("*") : new HashSet<>(groups);
    }

    private boolean isGroupAllowed(String gid) {
        return gid != null && (allowedGroups.contains("*") || allowedGroups.contains(gid));
    }

    /**
     * 单目标（供 /qq、/vxtb reply 回最近群用）：allowed-groups 只配一个具体群时固定那个，
     * 否则用最近活跃的群。
     */
    private String bridgeTarget() {
        if (allowedGroups.size() == 1 && !allowedGroups.contains("*")) {
            return allowedGroups.iterator().next();
        }
        return lastGroupOpenid;
    }

    /** game→QQ 聊天转发目标：allowed-groups 里的全部具体群（排除 "*"）。无具体群返回空。 */
    private List<String> bridgeTargets() {
        List<String> out = new java.util.ArrayList<>();
        for (String g : allowedGroups) {
            if (g != null && !"*".equals(g) && !g.trim().isEmpty()) out.add(g);
        }
        return out;
    }

    /**
     * 后台/手动回复最近活跃的群（优先主动消息，回退被动额度）。
     *
     * @return 是否成功（false=无 apiClient 且无可回复的被动额度）
     */
    public boolean replyToLastGroup(String content) {
        // 1) 尽力先走主动消息
        final ProactiveSender s = sender();
        final String gid = bridgeTarget();
        if (s != null && s.isReady() && isGroupAllowed(gid)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                // 主动失败 → 被动额度，再不行进队列
                if (!s.sendGroupMessage(gid, content) && !fallbackReply(content)) {
                    queueFallback(gid, content);
                }
            });
            return true;
        }

        // 2) 无主动通道 → 被动额度
        if (fallbackReply(content)) return true;

        // 3) 都走不通 → 挂起队列
        queueFallback(gid, content);
        return true;
    }

    /** 群 → 服：把群消息显示给所有在线玩家。 */
    public void onGroupMessage(BotMessageEvent event, String sender, String content) {
        // 解析消息中的 @提及：<@openid> → @昵称
        content = OpenidNameCache.getInstance().resolveMentions(content);
        content = filterChatlink(content); // QQ→游戏：群消息进游戏前过滤敏感词
        // 记录群 openid（供主动推送用）
        if (event.getGuildId() != null && !event.getGuildId().isEmpty()) {
            lastGroupOpenid = event.getGuildId();
        }
        lastGroupMsg.set(new Holder(event));
        Component line = Component.text(chatFormat + sender + "：" + content);
        for (Player p : proxy.getAllPlayers()) {
            p.sendMessage(line);
        }
        debug("[Chatlink] QQ→game 广播：发送者=" + sender
                + " 群=" + event.getGuildId()
                + " 在线玩家=" + proxy.getPlayerCount());
    }

    /** 服 → 群：玩家在游戏里打字 → 广播到 allowed-groups 里配置的每个具体群。 */
    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        List<String> targets = bridgeTargets();
        if (targets.isEmpty()) {
            // 没配具体群 → 不主动推（群服互联需把 allowed-groups 配成具体群）。这是最常见的「配了却不动」。
            debug("[Chatlink] game→QQ 跳过：allowed-groups 无具体群（当前=" + allowedGroups + "）");
            return;
        }

        String name = event.getPlayer().getUsername();
        // 游戏→QQ：玩家发言进群前过滤敏感词
        final String body = filterChatlink(event.getMessage());
        // 主动推送走 markdown（加粗名等修饰生效）；队列兜底走纯文本（被动通道会再统一转义）
        final String md = org.windy.xingtubot.common.util.ChatlinkFormat.markdown(gameFormat, name, body);
        final String plain = org.windy.xingtubot.common.util.ChatlinkFormat.plain(gameFormat, name, body);

        // 广播到每个具体群：尽力先主动消息，失败回退被动队列
        final ProactiveSender s = sender();
        final boolean ready = s != null && s.isReady();
        debug("[Chatlink] game→QQ 触发：玩家=" + name + " 目标群=" + targets
                + " sender=" + (ready ? "就绪" : "未就绪（将走队列兜底）"));
        for (String gid : targets) {
            final String g = gid;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                if (s != null && s.sendGroupMarkdown(g, md)) {
                    debug("[Chatlink] game→QQ 主动推送成功：群=" + g);
                    return;
                }
                debug("[Chatlink] game→QQ 主动通道未就绪/失败，进队列兜底：群=" + g);
                queueFallback(g, plain);
            });
        }
    }

    /** 被动模式兜底：挂靠最近群消息额度。返回是否挂靠成功。 */
    private boolean fallbackReply(String message) {
        Holder h = lastGroupMsg.get();
        if (h != null && h.expireAt >= System.currentTimeMillis()) {
            java.util.concurrent.CompletableFuture.runAsync(() -> h.event.reply(message));
            return true;
        }
        return false;
    }

    /** 最终兜底：进挂起队列（有目标群按群定向，否则全局）。 */
    private void queueFallback(String gid, String message) {
        if (gid != null) {
            PendingMessageQueue.getInstance().offer(gid, message);
        } else {
            PendingMessageQueue.getInstance().offer(message);
        }
    }
}
