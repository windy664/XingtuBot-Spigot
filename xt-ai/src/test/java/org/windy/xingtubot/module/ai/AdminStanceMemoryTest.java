package org.windy.xingtubot.module.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminStanceMemoryTest {

    @TempDir
    File tempDir;

    // ===== 压缩逻辑（核心：不能嵌套 [以往观点摘要]） =====

    @Test
    void compressNoNestedPrefix(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        String guild = "test-guild";

        // 插入超过 MAX_PER_GROUP(10) 条消息
        for (int i = 0; i < 15; i++) {
            mem.record(guild, "消息内容" + i);
        }

        // 验证：不应出现嵌套的 [以往观点摘要] [以往观点摘要]
        String ctx = mem.buildContext(guild);
        assertFalse(ctx.contains("[以往观点摘要] [以往观点摘要]"),
                "不应出现嵌套前缀，实际内容: " + ctx);
    }

    @Test
    void compressPreservesRecentMessages(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        String guild = "test-guild";

        for (int i = 0; i < 12; i++) {
            mem.record(guild, "消息" + i);
        }

        // 最近的消息应该还在
        String ctx = mem.buildContext(guild);
        assertTrue(ctx.contains("消息11"), "最近的消息应保留");
        assertTrue(ctx.contains("消息10"), "最近的消息应保留");
    }

    @Test
    void compressSummaryIsSingle(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        String guild = "test-guild";

        // 插入大量消息触发多次压缩
        for (int i = 0; i < 25; i++) {
            mem.record(guild, "观点" + i);
        }

        // 摘要应该只有一条（不嵌套）
        String ctx = mem.buildContext(guild);
        int summaryCount = countOccurrences(ctx, "[以往观点摘要]");
        assertTrue(summaryCount <= 1, "摘要应最多1条，实际: " + summaryCount);
    }

    // ===== 去重 =====

    @Test
    void deduplication(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        String guild = "test-guild";

        mem.record(guild, "相同消息");
        mem.record(guild, "相同消息");
        mem.record(guild, "相同消息");

        String ctx = mem.buildContext(guild);
        int count = countOccurrences(ctx, "相同消息");
        assertEquals(1, count, "相同消息应去重");
    }

    // ===== buildContext =====

    @Test
    void buildContextEmpty(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        assertEquals("", mem.buildContext("nonexistent"));
    }

    @Test
    void buildContextContainsHeader(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        mem.record("g1", "测试观点");

        String ctx = mem.buildContext("g1");
        assertTrue(ctx.contains("立场依据"), "应包含立场依据标题");
        assertTrue(ctx.contains("测试观点"), "应包含记录的观点");
    }

    // ===== 持久化 =====

    @Test
    void persistAndReload(@TempDir File dir) {
        AdminStanceMemory mem1 = new AdminStanceMemory(dir);
        mem1.record("g1", "持久化测试1");
        mem1.record("g1", "持久化测试2");

        // 重新加载
        AdminStanceMemory mem2 = new AdminStanceMemory(dir);
        String ctx = mem2.buildContext("g1");
        assertTrue(ctx.contains("持久化测试1"));
        assertTrue(ctx.contains("持久化测试2"));
    }

    // ===== null 安全 =====

    @Test
    void nullGuildId(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        assertDoesNotThrow(() -> mem.record(null, "test"));
        assertDoesNotThrow(() -> mem.record("g1", null));
        assertDoesNotThrow(() -> mem.record("g1", "  "));
    }

    // ===== mightConflict =====

    @Test
    void mightConflict(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        // tokenize 按标点/空格拆词，中文连续文本是一整个 token
        mem.record("g1", "服务器 应该 开启 创造模式");
        mem.record("g1", "管理员 不同意 开启 创造模式");

        // 包含两个以上共同词（>=2字符）→ 冲突
        assertTrue(mem.mightConflict("g1", "我觉得 应该 开启 创造模式"));
        // 不相关消息 → 不冲突
        assertFalse(mem.mightConflict("g1", "今天 天气 真好"));
    }

    // ===== 工具 =====

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
