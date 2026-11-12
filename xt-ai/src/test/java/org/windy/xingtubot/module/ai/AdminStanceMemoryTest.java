package org.windy.xingtubot.module.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.windy.xingtubot.common.ai.AiService;

import java.io.File;
import java.nio.file.Files;
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
        // 待归纳原文默认不注入，需要显式开启
        mem.configure(true, true, 5, null);
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

        mem.configure(true, true, 5, null);
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

        mem.configure(true, true, 5, null);
        mem.record("g1", "测试观点");

        String ctx = mem.buildContext("g1");
        assertTrue(ctx.contains("立场依据"), "应包含立场依据标题");
        assertTrue(ctx.contains("测试观点"), "应包含记录的观点");
    }

    // ===== 持久化 =====

    @Test
    void persistAndReload(@TempDir File dir) {
        AdminStanceMemory mem1 = new AdminStanceMemory(dir);

        mem1.configure(true, true, 5, null);
        mem1.record("g1", "持久化测试1");
        mem1.record("g1", "持久化测试2");
        // record() 只写内存，落盘由后台落盘器异步完成（避免在 QQ 网关线程做磁盘 IO），
        // 这里显式 flush 保证重新加载时数据已落盘
        mem1.flush();

        // 重新加载
        AdminStanceMemory mem2 = new AdminStanceMemory(dir);
        mem2.configure(true, true, 5, null);
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

    // ===== md 文件持久化 =====

    @Test
    void mdFileCreated(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        mem.record("g1", "md文件测试");
        mem.flush();

        assertTrue(new File(dir, "admin_stance.md").exists(), "应生成 admin_stance.md");
        assertFalse(new File(dir, "admin_stance.json").exists(), "不应再写 json");
    }

    // ===== 旧 json 迁移 =====

    @Test
    void migrationFromLegacyJson(@TempDir File dir) throws Exception {
        java.util.Map<String, List<String>> legacy = new java.util.LinkedHashMap<>();
        legacy.put("g1", java.util.Arrays.asList(
                "[以往观点摘要] 旧摘要：管理员支持创造模式",
                "新观点：反对PvP"));
        Files.write(new File(dir, "admin_stance.json").toPath(),
                new com.google.gson.Gson().toJson(legacy).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AdminStanceMemory mem = new AdminStanceMemory(dir);

        mem.configure(true, true, 5, null);
        String ctx = mem.buildContext("g1");
        assertTrue(ctx.contains("管理员支持创造模式"), "旧摘要应迁入长期记忆: " + ctx);
        assertTrue(ctx.contains("反对PvP"), "旧原始条目应迁入待归纳: " + ctx);
        assertTrue(new File(dir, "admin_stance.md").exists(), "迁移后应生成 md");
        assertFalse(new File(dir, "admin_stance.json").exists(), "迁移后应删除旧 json");
    }

    // ===== AI 自更新压缩 =====

    @Test
    void aiCompressRewritesMemory(@TempDir File dir) throws Exception {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        mem.setAiService(new AiService("k", "http://127.0.0.1:1", "test") {
            @Override
            public String chat(String userMessage) {
                return "```markdown\n管理员坚定支持创造模式\n```";
            }
        }, null);

        for (int i = 0; i < 6; i++) {
            mem.record("g1", "观点" + i);
        }

        // 等待异步压缩完成
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String ctx = mem.buildContext("g1");
            if (ctx.contains("坚定支持创造模式") && !ctx.contains("观点0")) break;
            Thread.sleep(50);
        }

        String ctx = mem.buildContext("g1");
        assertTrue(ctx.contains("坚定支持创造模式"), "AI 摘要应写入上下文: " + ctx);
        assertFalse(ctx.contains("观点0"), "压缩后待归纳应清空: " + ctx);
        assertFalse(ctx.contains("```"), "代码块围栏应被剥掉: " + ctx);
        assertFalse(ctx.contains("[以往观点摘要]"), "不应出现摘要前缀: " + ctx);

        // md 文件本身也要更新
        String md = new String(Files.readAllBytes(new File(dir, "admin_stance.md").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(md.contains("坚定支持创造模式"), "摘要应回写 md 文件: " + md);
        assertFalse(md.contains("观点0"), "待归纳应从 md 文件清除: " + md);
    }

    // ===== 隐私过滤 =====

    @Test
    void privateMessageNotRecorded(@TempDir File dir) throws Exception {
        AdminStanceMemory mem = new AdminStanceMemory(dir);

        mem.configure(true, true, 5, null);
        mem.record("g1", "服务器宣传帖曾被MC百科删除");
        mem.record("g1", "我最近经济拮据，服务器每月180续费才勉强续命");
        mem.record("g1", "我的Google账号被封了，Gemini登录不上");

        String ctx = mem.buildContext("g1");
        assertFalse(ctx.contains("经济拮据"), "财务隐私不应进入上下文: " + ctx);
        assertFalse(ctx.contains("Google账号被封"), "账号状态隐私不应进入上下文: " + ctx);
        assertTrue(ctx.contains("MC百科"), "正常的服务器相关发言应保留: " + ctx);

        // 也不应落盘
        mem.flush();
        String md = readMd(dir);
        assertFalse(md.contains("经济拮据"), "财务隐私不应写入 md: " + md);
        assertFalse(md.contains("Google账号被封"), "账号隐私不应写入 md: " + md);
    }

    @Test
    void privateSummarySanitized(@TempDir File dir) throws Exception {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        mem.setAiService(new AiService("k", "http://127.0.0.1:1", "test") {
            @Override
            public String chat(String userMessage) {
                return "管理员支持创造模式。经济拮据，常吃泡面。Google账号被封了。";
            }
        }, null);

        for (int i = 0; i < 6; i++) {
            mem.record("g1", "观点" + i);
        }

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (mem.buildContext("g1").contains("支持创造模式")) break;
            Thread.sleep(50);
        }

        String ctx = mem.buildContext("g1");
        assertTrue(ctx.contains("支持创造模式"), "正常立场应保留: " + ctx);
        assertFalse(ctx.contains("经济拮据"), "摘要里的财务隐私应被剔除: " + ctx);
        assertFalse(ctx.contains("泡面"), "摘要里的生活隐私应被剔除: " + ctx);
        assertFalse(ctx.contains("Google账号被封"), "摘要里的账号隐私应被剔除: " + ctx);
    }

    @Test
    void privateLegacyFileEntriesDropped(@TempDir File dir) throws Exception {
        java.util.Map<String, List<String>> legacy = new java.util.LinkedHashMap<>();
        legacy.put("g1", java.util.Arrays.asList(
                "[以往观点摘要] 管理员支持创造模式",
                "我的密码是123456",
                "新观点：反对PvP"));
        Files.write(new File(dir, "admin_stance.json").toPath(),
                new com.google.gson.Gson().toJson(legacy).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AdminStanceMemory mem = new AdminStanceMemory(dir);
        mem.configure(true, true, 5, null);
        String ctx = mem.buildContext("g1");
        assertTrue(ctx.contains("管理员支持创造模式"), "正常摘要应保留: " + ctx);
        assertFalse(ctx.contains("密码是123456"), "凭证类隐私应被丢弃: " + ctx);
        assertTrue(ctx.contains("反对PvP"), "正常条目应保留: " + ctx);
    }

    // ===== 开关与注入策略 =====

    @Test
    void disabledStanceRecordsNothing(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        mem.configure(false, false, 3, null);
        mem.record("g1", "管理员支持创造模式");

        assertEquals("", mem.buildContext("g1"), "关闭后不应注入任何立场");
    }

    @Test
    void pendingNotInjectedByDefault(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);
        mem.record("g1", "管理员支持创造模式");

        String ctx = mem.buildContext("g1");
        // 默认不注入逐字原文：待归纳只是记录，不进提示词
        assertFalse(ctx.contains("支持创造模式"), "默认不应注入待归纳原文: " + ctx);
        assertFalse(ctx.contains("群主最近说的"), "默认不应出现待归纳段落: " + ctx);

        // 显式开启后才会注入
        mem.configure(true, true, 3, null);
        String ctx2 = mem.buildContext("g1");
        assertTrue(ctx2.contains("群主最近说的"), "开启后应注入待归纳段落: " + ctx2);
    }

    @Test
    void contextCarriesUsageBoundary(@TempDir File dir) {
        AdminStanceMemory mem = new AdminStanceMemory(dir);

        mem.configure(true, true, 5, null);
        mem.record("g1", "管理员支持创造模式");

        String ctx = mem.buildContext("g1");
        assertTrue(ctx.contains("不要在群里复述"), "应提示不要复述群主私事: " + ctx);
        assertTrue(ctx.contains("不要为了表态而表态"), "应提示话题无关时不要硬表态: " + ctx);
    }

    // ===== 工具 =====

    private String readMd(File dir) throws Exception {
        File f = new File(dir, "admin_stance.md");
        if (!f.exists()) return "";
        return new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

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
