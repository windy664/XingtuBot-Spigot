package org.windy.xingtubot.common.util;

/**
 * QQ 群 Markdown 卡片构造器：让机器人发出的各类卡片长得像一家人。
 *
 * <p>统一审美：标题行用二级标题 + emoji，字段用「emoji + 粗体标签 + 全角空格 + 值」
 * 排出竖向节奏，简介/提示走引用块，底部可挂链接。空值（含占位符「—」）自动跳过，
 * 不会留下空字段。
 *
 * <pre>
 *   String md = Md.card("🌤", "北京 · 天气")
 *           .field("🌡", "温度", "18°C")
 *           .field("💧", "湿度", "60%")
 *           .quote("多云转晴，注意保暖")
 *           .link("查看详情", url)
 *           .build();
 * </pre>
 */
public final class Md {

    /** emoji 与值之间的全角空格，让冒号式对齐更整齐。 */
    private static final char WIDE_SPACE = '　';

    private final StringBuilder sb = new StringBuilder();

    private Md() {
    }

    /** 起一张卡片：`## {emoji} {title}`。 */
    public static Md card(String emoji, String title) {
        Md m = new Md();
        m.sb.append("## ");
        if (notBlank(emoji)) m.sb.append(emoji).append(' ');
        m.sb.append(title == null ? "" : title).append('\n');
        return m;
    }

    /** 副标题：标题下的一行灰色引用。 */
    public Md subtitle(String text) {
        if (notBlank(text)) sb.append("> ").append(text).append('\n');
        return this;
    }

    /** 字段行：`{emoji} **{label}**　{value}`。值空（或为「—」）则跳过。 */
    public Md field(String emoji, String label, String value) {
        if (!notBlank(value)) return this;
        if (notBlank(emoji)) sb.append(emoji).append(' ');
        sb.append("**").append(label).append("**").append(WIDE_SPACE).append(value.trim()).append('\n');
        return this;
    }

    /** 无 emoji 的字段行。 */
    public Md field(String label, String value) {
        return field(null, label, value);
    }

    /** 原样追加一行（自带换行）。 */
    public Md line(String text) {
        if (text != null) sb.append(text).append('\n');
        return this;
    }

    /** 引用块（简介、提示语）：前置一个空行让它和字段拉开。 */
    public Md quote(String text) {
        if (notBlank(text)) sb.append('\n').append("> ").append(text).append('\n');
        return this;
    }

    /** 底部链接，独占一行。 */
    public Md link(String text, String url) {
        if (notBlank(url)) sb.append('\n').append('[').append(text).append("](").append(url).append(')');
        return this;
    }

    /** 空行。 */
    public Md blank() {
        sb.append('\n');
        return this;
    }

    /** 产出 Markdown 文本，去掉首尾多余空白。 */
    public String build() {
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == '\n' || sb.charAt(end - 1) == ' ')) end--;
        int start = 0;
        while (start < end && (sb.charAt(start) == '\n' || sb.charAt(start) == ' ')) start++;
        return sb.substring(start, end);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty() && !"—".equals(s.trim());
    }

    /**
     * 把 markdown 里的单个 {@code \n} 补成 QQ 的软换行（行尾两空格 + {@code \n}）。
     *
     * <p>QQ 原生 markdown 会把单个 {@code \n} 吞成一行（软换行需行尾两空格），故所有卡片/模板文本
     * 出站前统一过一遍这里。<b>幂等</b>：已是两空格结尾的 {@code \n} 不再叠加（{@link #plain} 产出的
     * {@code "  \n"} 再过也不变），可安全在发送层集中调用。行尾其它空白后再补两空格无副作用。
     */
    public static String softBreaks(String md) {
        if (md == null || md.isEmpty() || md.indexOf('\n') < 0) return md;
        StringBuilder out = new StringBuilder(md.length() + 32);
        for (int i = 0; i < md.length(); i++) {
            char c = md.charAt(i);
            if (c == '\n') {
                int n = out.length();
                boolean twoSpaces = n >= 2 && out.charAt(n - 1) == ' ' && out.charAt(n - 2) == ' ';
                if (!twoSpaces) out.append("  ");
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 把纯文本安全转成 QQ markdown：转义会触发渲染的特殊字符 + 软换行。
     *
     * <p>用于让原本走纯文本的回复统一改走 markdown 通道，同时保证文本里的
     * {@code * _ ` [ ] # > ~ \} 等符号不被误当 markdown 语法、多行不被吞成一行。
     */
    public static String plain(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sb.append("  \n"); // 行尾两空格 = QQ markdown 软换行（否则单 \n 被吞）
                continue;
            }
            if (c == '\\' || c == '*' || c == '_' || c == '`' || c == '['
                    || c == ']' || c == '#' || c == '>' || c == '~') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
