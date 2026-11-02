package org.windy.xingtubot.common.chatlink;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatImage 模组玩家标记器（平台中立纯状态，无 PacketEvents/平台依赖）。
 *
 * <p>探测原理：装 ChatImage 的客户端入服时会经原版 {@code minecraft:register} 插件消息
 * 广播自己可接收的频道（chatimage:download_file_channel / chatimage:get_file_channel，
 * 见 kitUIN/ChatImage 源码）。平台探测层（xt-chatlink 的 PacketEvents 监听器，
 * 复用 xt-auth 内置并初始化的 PE 实例）扫到 {@code chatimage:*} 就 {@link #mark(UUID)}。
 *
 * <p>渲染方（Velocity 的 GroupChatLink / Bukkit 的 GuildMessageListener）
 * 按 {@link #wantsCicode(UUID)} 逐玩家分流：
 * true → 正文带 {@code [[CICode,...]]}（装 mod 的客户端渲染成聊天栏图片）；
 * false → {@code [图片]} 可点击链接（浏览器打开原图）。
 */
public final class ChatImageTracker {

    /** 图片消息渲染模式：auto=按探测结果逐玩家分流；cicode=全部 CICode；link=全部 [图片] 链接。 */
    public enum Mode { AUTO, CICODE, LINK }

    private final Set<UUID> detected = ConcurrentHashMap.newKeySet();
    private volatile Mode mode = Mode.AUTO;

    /** 解析配置项 chatlink-image-format（auto/cicode/link，未知值回退 auto）。 */
    public static Mode parseMode(String raw) {
        if (raw == null) return Mode.AUTO;
        switch (raw.trim().toLowerCase()) {
            case "cicode": return Mode.CICODE;
            case "link":   return Mode.LINK;
            default:       return Mode.AUTO; // auto/空/未知
        }
    }

    public void setMode(Mode mode) { this.mode = mode == null ? Mode.AUTO : mode; }

    public Mode getMode() { return mode; }

    /** 标记玩家装有 ChatImage（探测层在频道注册包里扫到 chatimage:* 时调用）。 */
    public void mark(UUID playerId) { if (playerId != null) detected.add(playerId); }

    /** 玩家退服/断线后清除标记（频道注册每次入服都会重新广播，重进即恢复探测）。 */
    public void forget(UUID playerId) { if (playerId != null) detected.remove(playerId); }

    public boolean has(UUID playerId) { return playerId != null && detected.contains(playerId); }

    /** 该玩家是否应收到 CICode 文本（true=CICode；false=[图片] 可点击链接）。 */
    public boolean wantsCicode(UUID playerId) {
        switch (mode) {
            case CICODE: return true;
            case LINK:   return false;
            default:     return has(playerId); // auto：探测到才给 CICode
        }
    }
}
