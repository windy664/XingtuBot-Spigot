package org.windy.xingtubot.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 把二维码矩阵渲染成「聊天栏字符画」（平台无关，输出带 § 颜色码的行）。
 *
 * <p>用半块字符 {@code ▀▄█} 让<b>一个字符表示上下两个</b>二维码模块，高度直接减半——
 * 这是聊天里能塞下二维码的关键（更小）。四种组合都用同族方块字（{@code ▀▄█}），
 * Minecraft 默认字体里它们等宽，横向不会错位（否则二维码会变形、扫不出）。
 *
 * <p>正色渲染：白模块 = 白色字符（{@code §f}），黑模块 = 黑色（{@code §0}，融进聊天/tooltip 深色背景）。
 *
 * <p>⚠️ MC 默认字体并非严格等宽，聊天二维码仍可能轻微变形；内容越短（version 越低）越稳，
 * 建议配合容错。最可靠的仍是地图渲染。
 */
public final class QrChat {

    private QrChat() {
    }

    /** 渲染成多行（每行一个带 § 颜色码的字符串）。qr 为 null/空返回空列表。 */
    public static List<String> render(boolean[][] qr) {
        List<String> lines = new ArrayList<>();
        if (qr == null || qr.length == 0) return lines;
        int n = qr.length;

        for (int y = 0; y < n; y += 2) {
            StringBuilder sb = new StringBuilder();
            char lastColor = 0; // 颜色码去重，缩短消息
            for (int x = 0; x < n; x++) {
                boolean topBlack = qr[y][x];                       // true=黑模块
                boolean botBlack = (y + 1 < n) && qr[y + 1][x];
                boolean topWhite = !topBlack;
                boolean botWhite = !botBlack;

                char color;
                char glyph;
                if (topWhite && botWhite) {
                    color = 'f'; glyph = '█';
                } else if (topWhite) {            // 上白下黑
                    color = 'f'; glyph = '▀';
                } else if (botWhite) {            // 上黑下白
                    color = 'f'; glyph = '▄';
                } else {                          // 上黑下黑
                    color = '0'; glyph = '█';
                }
                if (color != lastColor) {
                    sb.append('§').append(color);
                    lastColor = color;
                }
                sb.append(glyph);
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /** 渲染成单个多行字符串（用 {@code \n} 连接），便于塞进 hover 提示框。 */
    public static String renderJoined(boolean[][] qr) {
        List<String> lines = render(qr);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
