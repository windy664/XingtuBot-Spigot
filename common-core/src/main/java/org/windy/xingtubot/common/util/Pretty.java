package org.windy.xingtubot.common.util;

/**
 * 控制台排版小工具：按「显示宽度」对齐（CJK / 全角 / emoji 算 2 格，其余算 1 格）。
 *
 * <p>用于启动摘要等需要列对齐的日志输出。不画右边框——CJK 与 emoji 的实际渲染宽度
 * 因终端字体而异，闭合框几乎不可能跨终端对齐，左对齐分栏才是稳妥的好看。
 */
public final class Pretty {

    private Pretty() {
    }

    /** 字符串的显示宽度（CJK / 全角 / emoji 记 2，其余记 1）。 */
    public static int width(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += isWide(cp) ? 2 : 1;
        }
        return w;
    }

    /** 右侧补空格到目标显示宽度（不足才补，超出原样返回）。 */
    public static String padEnd(String s, int displayWidth) {
        int pad = displayWidth - width(s);
        if (pad <= 0) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) sb.append(' ');
        return sb.toString();
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)    // 韩文字母
                || (cp >= 0x2E80 && cp <= 0xA4CF) // CJK 部首 / 汉字 / 假名 等
                || (cp >= 0xAC00 && cp <= 0xD7A3) // 韩文音节
                || (cp >= 0xF900 && cp <= 0xFAFF) // CJK 兼容汉字
                || (cp >= 0xFE30 && cp <= 0xFE4F) // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60) // 全角 ASCII
                || (cp >= 0xFFE0 && cp <= 0xFFE6) // 全角符号
                || (cp >= 0x1F300 && cp <= 0x1FAFF) // emoji
                || (cp >= 0x20000 && cp <= 0x3FFFD); // CJK 扩展 B+
    }
}
