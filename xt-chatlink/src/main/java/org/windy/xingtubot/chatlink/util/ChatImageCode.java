package org.windy.xingtubot.chatlink.util;

import java.util.List;

/**
 * ChatImage CICode helper used by the chatlink extension.
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
