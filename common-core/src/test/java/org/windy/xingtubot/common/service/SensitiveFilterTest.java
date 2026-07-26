package org.windy.xingtubot.common.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveFilterTest {

    private static SensitiveFilter createFilter(List<String> localWords, List<String> ignoredPunct) {
        return new SensitiveFilter(
                true,
                localWords,
                Collections.emptyList(),
                Collections.emptyList(), // 不加载云端，测试本地逻辑
                ignoredPunct,
                '*',
                null
        );
    }

    // ===== 基本匹配 =====

    @Test
    void exactMatch() {
        SensitiveFilter f = createFilter(Arrays.asList("fuck", "shit"), Collections.emptyList());
        assertEquals("****", f.filter("fuck"));   // fuck=4字符
        assertEquals("****", f.filter("Fuck"));
        assertEquals("****", f.filter("shit"));   // shit=4字符
    }

    @Test
    void matchInsideText() {
        SensitiveFilter f = createFilter(Arrays.asList("bad"), Collections.emptyList());
        assertEquals("this is *** word", f.filter("this is bad word")); // bad=3字符
    }

    @Test
    void multipleMatches() {
        SensitiveFilter f = createFilter(Arrays.asList("a", "b"), Collections.emptyList());
        assertEquals("* *", f.filter("a b"));
    }

    // ===== 忽略标点穿插 =====

    @Test
    void punctIgnored() {
        List<String> ignored = Arrays.asList(".", "-", "_");
        SensitiveFilter f = createFilter(Arrays.asList("fuck"), ignored);
        // f.u.c.k → 去掉标点后是 fuck → 匹配，标点保留只替换字母
        assertEquals("*.*.*.*", f.filter("f.u.c.k"));
        // f-u-c-k → 匹配
        assertEquals("*-*-*-*", f.filter("f-u-c-k"));
    }

    @Test
    void punctNotIgnoredWhenNotConfigured() {
        // 没配置忽略标点 → f.u.c.k 不匹配 fuck
        SensitiveFilter f = createFilter(Arrays.asList("fuck"), Collections.emptyList());
        assertEquals("f.u.c.k", f.filter("f.u.c.k"));
    }

    // ===== 边界情况 =====

    @Test
    void nullInput() {
        SensitiveFilter f = createFilter(Arrays.asList("x"), Collections.emptyList());
        assertNull(f.filter(null));
    }

    @Test
    void emptyInput() {
        SensitiveFilter f = createFilter(Arrays.asList("x"), Collections.emptyList());
        assertEquals("", f.filter(""));
    }

    @Test
    void disabledFilter() {
        SensitiveFilter f = new SensitiveFilter(
                false, // disabled
                Arrays.asList("fuck"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                '*',
                null
        );
        assertEquals("fuck", f.filter("fuck"));
    }

    @Test
    void emptyWordList() {
        SensitiveFilter f = createFilter(Collections.emptyList(), Collections.emptyList());
        assertEquals("hello world", f.filter("hello world"));
    }

    @Test
    void noMatch() {
        SensitiveFilter f = createFilter(Arrays.asList("xyz"), Collections.emptyList());
        assertEquals("hello world", f.filter("hello world"));
    }

    // ===== 词库统计 =====

    @Test
    void wordCount() {
        SensitiveFilter f = createFilter(Arrays.asList("a", "b", "c"), Collections.emptyList());
        assertEquals(3, f.wordCount());
    }

    @Test
    void isEnabled() {
        SensitiveFilter on = createFilter(Collections.emptyList(), Collections.emptyList());
        assertTrue(on.isEnabled());

        SensitiveFilter off = new SensitiveFilter(false,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), '*', null);
        assertFalse(off.isEnabled());
    }

    // ===== 大小写 =====

    @Test
    void caseInsensitive() {
        SensitiveFilter f = createFilter(Arrays.asList("bad"), Collections.emptyList());
        assertEquals("***", f.filter("BAD"));
        assertEquals("***", f.filter("Bad"));
        assertEquals("***", f.filter("bAd"));
    }
}
