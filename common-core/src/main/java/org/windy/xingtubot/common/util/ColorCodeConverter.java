package org.windy.xingtubot.common.util;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 颜色代码转换工具。
 * §0-§f 颜色 → QQ Markdown 的 &lt;font color&gt; 标签。
 * §l/§o/§m 格式 → Markdown 的粗体/斜体/删除线。
 */
public final class ColorCodeConverter {

    private ColorCodeConverter() {
    }

    // 蓝色系：可转为 QQ Markdown 蓝色链接
    private static final Set<Character> BLUE_CODES = new HashSet<>();
    static {
        BLUE_CODES.add('1'); // dark blue
        BLUE_CODES.add('9'); // blue
    }

    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)[§&]([0-9a-fk-or])");

    /**
     * 把 MC 颜色代码转成 QQ Markdown 格式。
     * 蓝色(§1/§9) → 蓝色伪链接 [文字](文字)，其他颜色 → 去符号留纯文本。
     * 粗体/斜体/删除线 → Markdown 语法。
     */
    public static String toMarkdown(String text) {
        if (text == null || text.isEmpty()) return text;

        StringBuilder sb = new StringBuilder();
        StringBuilder segmentBuf = new StringBuilder(); // 当前段累积
        boolean isBlue = false;                         // 当前段是否蓝色

        Matcher m = COLOR_PATTERN.matcher(text);
        int lastEnd = 0;

        while (m.find()) {
            // 匹配前的普通文本累积到当前段
            String before = text.substring(lastEnd, m.start());
            if (!before.isEmpty()) segmentBuf.append(before);
            lastEnd = m.end();

            char code = Character.toLowerCase(m.group(1).charAt(0));

            // 先刷出上一段
            if (segmentBuf.length() > 0) {
                sb.append(flushSegment(segmentBuf.toString(), isBlue));
                segmentBuf.setLength(0);
            }

            if (BLUE_CODES.contains(code)) {
                isBlue = true;
            } else if (COLOR_MAP_OLD(code)) {
                isBlue = false; // 其他颜色：后面去掉符号即可
            } else {
                // 格式码或重置
                isBlue = false;
                switch (code) {
                    case 'l': sb.append("**");  break;
                    case 'o': sb.append("*");   break;
                    case 'm': sb.append("~~");  break;
                    case 'n': break;
                    case 'k': break;
                    case 'r': break;
                }
            }
        }

        // 剩余文本
        String rest = text.substring(lastEnd);
        if (!rest.isEmpty()) segmentBuf.append(rest);
        if (segmentBuf.length() > 0) {
            sb.append(flushSegment(segmentBuf.toString(), isBlue));
        }

        return sb.toString();
    }

    /** 刷出一段文字：蓝色用链接，其他去颜色码。 */
    private static String flushSegment(String text, boolean blue) {
        if (text == null || text.isEmpty()) return "";
        String clean = stripColor(text);
        if (clean.isEmpty()) return "";
        if (blue) {
            return "[" + clean + "](" + clean + ")";
        }
        return clean;
    }

    /** 是否是其他颜色码（非蓝色、非格式码）。 */
    private static boolean COLOR_MAP_OLD(char code) {
        return "02345678abcdef".indexOf(code) >= 0 && !BLUE_CODES.contains(code);
    }

    /**
     * 去掉所有 MC 颜色代码（纯文本用）。
     */
    public static String stripColor(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)[§&][0-9A-FK-OR]", "");
    }
}
