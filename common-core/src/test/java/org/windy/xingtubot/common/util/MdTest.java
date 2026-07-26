package org.windy.xingtubot.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MdTest {

    @Test
    void basicCard() {
        String md = Md.card("🌤", "北京 · 天气")
                .field("🌡", "温度", "18°C")
                .field("💧", "湿度", "60%")
                .build();

        assertTrue(md.contains("## 🌤 北京 · 天气"));
        assertTrue(md.contains("**温度**"));
        assertTrue(md.contains("18°C"));
        assertTrue(md.contains("**湿度**"));
        assertTrue(md.contains("60%"));
    }

    @Test
    void emptyFieldSkipped() {
        String md = Md.card("📦", "测试")
                .field("📌", "状态", "")
                .field("👤", "作者", "Wind")
                .build();

        assertFalse(md.contains("状态"), "空字段应跳过");
        assertTrue(md.contains("作者"));
    }

    @Test
    void nullFieldSkipped() {
        String md = Md.card("📦", "测试")
                .field("📌", "状态", null)
                .field("👤", "作者", "Wind")
                .build();

        assertFalse(md.contains("状态"));
        assertTrue(md.contains("作者"));
    }

    @Test
    void dashFieldSkipped() {
        // "—" 是占位符，应跳过
        String md = Md.card("📦", "测试")
                .field("📌", "状态", "—")
                .build();

        assertFalse(md.contains("状态"));
    }

    @Test
    void links() {
        String md = Md.card("📦", "测试")
                .link("查看详情", "https://example.com")
                .build();

        assertTrue(md.contains("[查看详情](https://example.com)"));
    }

    @Test
    void emptyLinkSkipped() {
        String md = Md.card("📦", "测试")
                .link("空链接", "")
                .build();

        assertFalse(md.contains("空链接"));
    }

    @Test
    void quote() {
        String md = Md.card("📦", "测试")
                .quote("这是一段引用")
                .build();

        assertTrue(md.contains("> 这是一段引用"));
    }

    @Test
    void buildTrimsWhitespace() {
        String md = Md.card("📦", "测试")
                .line("")
                .line("")
                .build();

        // 不应以空白行开头或结尾
        assertFalse(md.startsWith("\n"));
        assertFalse(md.endsWith("\n"));
    }

    @Test
    void softBreaks() {
        String input = "line1\nline2\nline3";
        String result = Md.softBreaks(input);
        // 每个 \n 前应有两空格
        assertTrue(result.contains("  \nline2"));
        assertTrue(result.contains("  \nline3"));
    }

    @Test
    void softBreaksIdempotent() {
        String input = "line1  \nline2  \nline3";
        String result = Md.softBreaks(input);
        assertEquals(input, result); // 已有两空格，不再叠加
    }

    @Test
    void plainEscapesSpecialChars() {
        String result = Md.plain("hello *world* [link]");
        assertTrue(result.contains("\\*world\\*"));
        assertTrue(result.contains("\\[link\\]"));
    }

    @Test
    void plainAddsSoftBreaks() {
        String result = Md.plain("a\nb");
        assertTrue(result.contains("  \n"));
    }

    @Test
    void nullPlain() {
        assertNull(Md.plain(null));
    }
}
