package org.windy.xingtubot.common.util;

/**
 * 游戏→QQ 群服互联聊天行的格式化（三端共用，避免各平台各写一份导致样式分叉）。
 *
 * <p>模板支持两个占位符：{@code {player}}=玩家名、{@code {message}}=玩家发言正文。
 * 默认 {@code 💬 **{player}**：{message}}——emoji + 加粗玩家名 + 全角冒号 + 正文。
 *
 * <p>两种产出，对应两条发送通道：
 * <ul>
 *   <li>{@link #markdown}：主动推送用。走 {@code ProactiveSender.sendGroupMarkdown}（不转义），
 *       故玩家名/正文各自 {@link Md#plain} 转义防注入，模板里的 {@code **} 等 markdown 修饰保留生效。</li>
 *   <li>{@link #plain}：队列兜底用。被动 {@code replyText} 会再统一 {@link Md#plain} 转义，
 *       此处先去掉模板里的 {@code **}（否则会被转义成字面星号），玩家名/正文原样放入。</li>
 * </ul>
 */
public final class ChatlinkFormat {

    /** 默认聊天行模板：emoji + 加粗玩家名 + 全角冒号 + 正文。 */
    public static final String DEFAULT = "💬 **{player}**：{message}";

    private ChatlinkFormat() {
    }

    private static String template(String template) {
        return (template == null || template.trim().isEmpty()) ? DEFAULT : template;
    }

    /** 主动推送用：按模板渲染 markdown，玩家名与正文都转义防注入。 */
    public static String markdown(String template, String player, String message) {
        return template(template)
                .replace("{player}", Md.plain(player == null ? "" : player))
                .replace("{message}", Md.plain(message == null ? "" : message));
    }

    /** 队列兜底用：去掉模板里的 markdown 加粗修饰，产出纯文本（后续被动通道再统一转义）。 */
    public static String plain(String template, String player, String message) {
        return template(template)
                .replace("**", "")
                .replace("{player}", player == null ? "" : player)
                .replace("{message}", message == null ? "" : message);
    }
}
