package org.windy.xingtubot.chatlink.util;

import org.windy.xingtubot.common.util.Md;

/**
 * Formatting helper for game-to-QQ chatlink messages.
 */
public final class ChatlinkFormat {

    public static final String DEFAULT = "💬 **{player}**：{message}";

    private ChatlinkFormat() {
    }

    private static String template(String template) {
        return template == null || template.trim().isEmpty() ? DEFAULT : template;
    }

    public static String markdown(String template, String player, String message) {
        return template(template)
                .replace("{player}", Md.plain(player == null ? "" : player))
                .replace("{message}", Md.plain(message == null ? "" : message));
    }

    public static String plain(String template, String player, String message) {
        return template(template)
                .replace("**", "")
                .replace("{player}", player == null ? "" : player)
                .replace("{message}", message == null ? "" : message);
    }
}
