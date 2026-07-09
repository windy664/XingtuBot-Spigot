package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.poll.OpenidNameCache;
import org.windy.xingtubot.common.queue.PendingMessageQueue;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * BungeeCord 群服互联（与 GroupChatLink 功能对等）。
 */
public class BungeeCordGroupChatLink implements Listener {

    private final ProxyServer proxy;
    private final String chatFormat;
    // game→QQ 聊天行 markdown 模板（占位符 {player}/{message}）。
    private volatile String gameFormat = org.windy.xingtubot.common.util.ChatlinkFormat.DEFAULT;
    // 主动消息能力：存「取 sender 的供给器」而非 sender 本身，每次发送时现取。
    // 避免在本插件 onEnable 那一刻核心还没 registerService(ProactiveSender) → 缓存了 null 且永不自愈。
    private volatile java.util.function.Supplier<ProactiveSender> senderSupplier;
    private volatile String lastGroupOpenid;
    private volatile Set<String> allowedGroups = Collections.singleton("*");
    private volatile org.windy.xingtubot.common.service.SensitiveFilter sensitiveFilter;
    private volatile org.windy.xingtubot.common.platform.BotLogger logger; // 调试日志，可 null
    private final AtomicReference<Holder> lastGroupMsg = new AtomicReference<>();

    private static final class Holder {
        final BotMessageEvent event;
        final long expireAt;
        Holder(BotMessageEvent event) {
            this.event = event;
            this.expireAt = System.currentTimeMillis() + 5 * 60 * 1000;
        }
    }

    public BungeeCordGroupChatLink(ProxyServer proxy, net.md_5.bungee.api.plugin.Plugin plugin, String chatFormat) {
        this.proxy = proxy;
        this.chatFormat = chatFormat == null ? "§b[QQ群] §f" : chatFormat;
        proxy.getPluginManager().registerListener(plugin, this);
    }

    /**
     * 注入「主动消息能力供给器」（一般传 {@code () -> host.getService(ProactiveSender.class)}）。
     * 每次发送时现取,故不受插件加载/注册顺序影响。
     */
    public void setSender(java.util.function.Supplier<ProactiveSender> senderSupplier) { this.senderSupplier = senderSupplier; }
    public void setSensitiveFilter(org.windy.xingtubot.common.service.SensitiveFilter filter) { this.sensitiveFilter = filter; }

    /** 设置 game→QQ 聊天行 markdown 模板（占位符 {player}/{message}；空/null 用默认）。 */
    public void setGameFormat(String format) {
        this.gameFormat = (format == null || format.trim().isEmpty())
                ? org.windy.xingtubot.common.util.ChatlinkFormat.DEFAULT : format;
    }

    /** 注入调试日志器（仅 DebugFlag 开启时打点；传 null 关闭）。 */
    public void setLogger(org.windy.xingtubot.common.platform.BotLogger logger) { this.logger = logger; }

    /** 现取主动发送器（供给器为 null 或取出 null 都返回 null,由调用方回退队列）。 */
    private ProactiveSender sender() {
        java.util.function.Supplier<ProactiveSender> sp = this.senderSupplier;
        return sp != null ? sp.get() : null;
    }

    /** 调试日志：有 logger 且 DebugFlag 开启才输出（实时跟随 /xtb debug）。 */
    private void debug(String msg) {
        org.windy.xingtubot.common.platform.BotLogger l = this.logger;
        if (l != null && org.windy.xingtubot.common.DebugFlag.isOn()) l.info(msg);
    }

    public void setAllowedGroups(List<String> groups) {
        this.allowedGroups = (groups == null || groups.isEmpty())
                ? Collections.singleton("*") : new HashSet<>(groups);
    }

    private String filterChatlink(String text) {
        org.windy.xingtubot.common.service.SensitiveFilter f = this.sensitiveFilter;
        return f != null ? f.filter(text) : text;
    }

    private String bridgeTarget() {
        if (allowedGroups.size() == 1 && !allowedGroups.contains("*")) {
            return allowedGroups.iterator().next();
        }
        return lastGroupOpenid;
    }

    private List<String> bridgeTargets() {
        List<String> out = new ArrayList<>();
        for (String g : allowedGroups) {
            if (g != null && !"*".equals(g) && !g.trim().isEmpty()) out.add(g);
        }
        return out;
    }

    public boolean replyToLastGroup(String content) {
        final ProactiveSender s = sender();
        final String gid = bridgeTarget();
        if (s != null && s.isReady() && isGroupAllowed(gid)) {
            CompletableFuture.runAsync(() -> {
                if (!s.sendGroupMessage(gid, content) && !fallbackReply(content)) {
                    queueFallback(gid, content);
                }
            });
            return true;
        }
        if (fallbackReply(content)) return true;
        queueFallback(gid, content);
        return true;
    }

    private boolean isGroupAllowed(String gid) {
        return gid != null && (allowedGroups.contains("*") || allowedGroups.contains(gid));
    }

    public void onGroupMessage(BotMessageEvent event, String sender, String content) {
        content = OpenidNameCache.getInstance().resolveMentions(content);
        content = filterChatlink(content);
        if (event.getGuildId() != null && !event.getGuildId().isEmpty()) {
            lastGroupOpenid = event.getGuildId();
        }
        lastGroupMsg.set(new Holder(event));
        String line = chatFormat + sender + "：" + content;
        TextComponent component = new TextComponent(line);
        for (ProxiedPlayer p : proxy.getPlayers()) {
            p.sendMessage(component);
        }
        debug("[Chatlink] QQ→game 广播：发送者=" + sender
                + " 群=" + event.getGuildId()
                + " 在线玩家=" + proxy.getPlayers().size());
    }

    @EventHandler
    public void onPlayerChat(ChatEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getSender() instanceof ProxiedPlayer)) return;
        // 只处理玩家聊天（非命令）
        if (event.isCommand()) return;

        List<String> targets = bridgeTargets();
        if (targets.isEmpty()) {
            // 没配具体群 → 不主动推（群服互联需把 allowed-groups 配成具体群）。最常见的「配了却不动」。
            debug("[Chatlink] game→QQ 跳过：allowed-groups 无具体群（当前=" + allowedGroups + "）");
            return;
        }

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String name = player.getName();
        final String body = filterChatlink(event.getMessage());
        // 主动推送走 markdown（加粗名等修饰生效）；队列兜底走纯文本（被动通道会再统一转义）
        final String md = org.windy.xingtubot.common.util.ChatlinkFormat.markdown(gameFormat, name, body);
        final String plain = org.windy.xingtubot.common.util.ChatlinkFormat.plain(gameFormat, name, body);

        final ProactiveSender s = sender();
        final boolean ready = s != null && s.isReady();
        debug("[Chatlink] game→QQ 触发：玩家=" + name + " 目标群=" + targets
                + " sender=" + (ready ? "就绪" : "未就绪（将走队列兜底）"));
        for (String gid : targets) {
            final String g = gid;
            CompletableFuture.runAsync(() -> {
                if (s != null && s.sendGroupMarkdown(g, md)) {
                    debug("[Chatlink] game→QQ 主动推送成功：群=" + g);
                    return;
                }
                debug("[Chatlink] game→QQ 主动通道未就绪/失败，进队列兜底：群=" + g);
                queueFallback(g, plain);
            });
        }
    }

    private boolean fallbackReply(String message) {
        Holder h = lastGroupMsg.get();
        if (h != null && h.expireAt >= System.currentTimeMillis()) {
            CompletableFuture.runAsync(() -> h.event.reply(message));
            return true;
        }
        return false;
    }

    private void queueFallback(String gid, String message) {
        if (gid != null) {
            PendingMessageQueue.getInstance().offer(gid, message);
        } else {
            PendingMessageQueue.getInstance().offer(message);
        }
    }
}
