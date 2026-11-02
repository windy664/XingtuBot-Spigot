package org.windy.xingtubot.common.image;

import java.util.List;

/**
 * ChatImage（https://github.com/kitUIN/ChatImage）CICode 拼码工具。
 *
 * <p>群服互联 QQ→游戏 方向把群图片 URL 拼成 {@code [[CICode,url=...,name=...]]}：
 * 装 ChatImage mod 的客户端自动渲染为聊天栏图片，没装的看到原始文本，游戏照常。
 * 放 common-core 是因为 Velocity 端（BotCommandHandler 的 gameBridge）与
 * Bukkit 端（xt-chatlink 的 GuildMessageListener）都要用。
 */
public final class ChatImageCode {

    private ChatImageCode() {
    }

    public static String code(String url, String name) {
        if (url == null || url.trim().isEmpty()) return "";
        String n = name == null || name.trim().isEmpty() ? "图片" : name.trim();
        n = n.replace(",", " ").replace("[", " ").replace("]", " ");
        return "[[CICode,url=" + url.trim() + ",name=" + n + "]]";
    }

    public static String appendTo(String content, List<String> urls, String name) {
        String base = content == null ? "" : content;
        if (urls == null || urls.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base);
        for (String url : urls) {
            String code = code(url, name);
            if (code.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(code);
        }
        return sb.toString();
    }
}
