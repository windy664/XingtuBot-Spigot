package org.windy.xingtubot.common.util;

import java.util.List;

/**
 * ChatImage 模组的聊天图片码（CICode）工具。<b>纯文本协议、零服务端依赖</b>：
 * 把 {@code [[CICode,url=...,name=...]]} 拼进聊天消息——装了 ChatImage 客户端 mod 的玩家
 * 会把它渲染成图片，没装的玩家看到这串文本（URL 可读），游戏一切照常（优雅降级）。
 *
 * @see <a href="https://github.com/kitUIN/ChatImage">kitUIN/ChatImage</a>
 */
public final class ChatImageCode {

    private ChatImageCode() {
    }

    /** 单张图片的 CICode 文本。url 空则返回空串。 */
    public static String code(String url, String name) {
        if (url == null || url.trim().isEmpty()) return "";
        String n = (name == null || name.trim().isEmpty()) ? "图片" : name.trim();
        // name 里的逗号/中括号会破坏 CICode 解析，做最简清洗
        n = n.replace(",", " ").replace("[", " ").replace("]", " ");
        return "[[CICode,url=" + url.trim() + ",name=" + n + "]]";
    }

    /**
     * 把多张图片的 CICode 追加到正文后（用空格分隔）。{@code content} 可空（纯图片消息无文字）。
     * 无图片时原样返回 content（null 归一为 ""）。
     */
    public static String appendTo(String content, List<String> urls, String name) {
        String base = content == null ? "" : content;
        if (urls == null || urls.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base);
        for (String url : urls) {
            String c = code(url, name);
            if (c.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c);
        }
        return sb.toString();
    }
}
