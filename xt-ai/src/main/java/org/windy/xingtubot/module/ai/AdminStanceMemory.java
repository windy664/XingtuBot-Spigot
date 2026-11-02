package org.windy.xingtubot.module.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 超管观点长期记忆：记录超管在群聊中表达的立场和观点，
 * 用于让 AI 在群聊中自然地站在超管一方。
 *
 * <p>持久化为人类可读的 {@code admin_stance.md}，每群一节：
 * <ol>
 *   <li><b>摘要</b>——AI 维护的长期立场记忆；</li>
 *   <li><b>待归纳</b>——尚未压缩的原始发言（{@code - } 列表）。</li>
 * </ol>
 * 待归纳攒够阈值时，把「旧摘要 + 新发言」整体交给 LLM 重写压缩并回写 md，
 * 相当于 AI 自己更新这份文件；管理员也可以手动编辑（手动编辑在下次压缩前始终生效）。
 * <p>旧的 {@code admin_stance.json} 首次加载时自动迁移到 md 并删除。
 */
public final class AdminStanceMemory {

    private static final int MAX_PENDING_PER_GROUP = 20;      // 待归纳条数上限（超限丢最旧）
    private static final int MAX_SUMMARY_CHARS = 600;         // LLM 压缩产出的摘要长度上限
    private static final int MAX_LOADED_SUMMARY_CHARS = 1000; // 加载时的摘要长度上限（防 system prompt 膨胀）
    private static final int COMPRESS_THRESHOLD = 5;          // 待归纳超过此值触发 LLM 压缩
    private static final Gson GSON = new Gson();
    private static final String SUMMARY_PREFIX = "[以往观点摘要] ";
    private static final String PENDING_HEADING = "### 待归纳";

    private static final class GroupMemory {
        volatile String summary = "";
        final List<String> pending = Collections.synchronizedList(new ArrayList<>());
    }

    private final ConcurrentHashMap<String, GroupMemory> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> pendingCompress = new ConcurrentHashMap<>();
    private final File file;
    private final File legacyFile;
    private volatile AiService aiService;
    private volatile BotLogger logger;
    private volatile boolean migratedFromJson;

    public AdminStanceMemory(File dataDir) {
        this.file = new File(dataDir, "admin_stance.md");
        this.legacyFile = new File(dataDir, "admin_stance.json");
        load();
    }

    /** 注入 LLM 服务，启用自动压缩（不注入则只保留待归纳原文）。 */
    public void setAiService(AiService aiService, BotLogger logger) {
        this.aiService = aiService;
        this.logger = logger;
        if (migratedFromJson && logger != null) {
            logger.info("[AI] 超管立场已从 admin_stance.json 迁移到 admin_stance.md");
        }
    }

    /**
     * 记录一条超管发言（自动持久化，攒够阈值异步让 LLM 压缩进摘要）。
     */
    public void record(String guildId, String content) {
        if (guildId == null || content == null || content.trim().isEmpty()) return;
        String entry = content.trim().replaceAll("\\s+", " ");
        GroupMemory g = groups.computeIfAbsent(guildId, k -> new GroupMemory());
        synchronized (g.pending) {
            // 去重（只看最后一条，与旧逻辑一致）
            if (!g.pending.isEmpty() && g.pending.get(g.pending.size() - 1).equals(entry)) return;
            g.pending.add(entry);
            while (g.pending.size() > MAX_PENDING_PER_GROUP) g.pending.remove(0);
        }
        save();
        maybeCompressAsync(guildId, g);
    }

    /**
     * 待归纳超过阈值时，异步调用 LLM 把「旧摘要 + 新发言」重写成新摘要。
     * 不阻塞 record() 调用。
     */
    private void maybeCompressAsync(String guildId, GroupMemory g) {
        if (aiService == null) return; // 无 LLM，只保留原文
        List<String> pendingSnapshot;
        synchronized (g.pending) {
            if (g.pending.size() <= COMPRESS_THRESHOLD) return;
            pendingSnapshot = new ArrayList<>(g.pending);
        }
        if (pendingCompress.putIfAbsent(guildId, Boolean.TRUE) != null) return; // 已有压缩在跑
        String summarySnapshot = g.summary;

        new Thread(() -> {
            try {
                doCompress(guildId, summarySnapshot, pendingSnapshot);
            } finally {
                pendingCompress.remove(guildId);
            }
        }, "admin-stance-compress-" + guildId).start();
    }

    /**
     * LLM 压缩：把当前 md 的摘要与待归纳原文合并，重写出新的摘要正文并回写文件。
     */
    private void doCompress(String guildId, String oldSummary, List<String> pendingSnapshot) {
        StringBuilder input = new StringBuilder();
        for (String item : pendingSnapshot) {
            input.append("- ").append(item).append("\n");
        }

        String prompt = "下面是某个QQ群里管理员立场的长期记忆（Markdown），以及他最近说的新话。\n" +
                "请把新话里的立场合并进记忆：保留具体态度、偏好和结论，去掉重复、过时或无关细节，" +
                "输出更新后的精炼 Markdown（200字以内；不要代码块包裹，不要任何标题行）。直接输出正文。\n\n" +
                "【当前记忆】\n" + (oldSummary.isEmpty() ? "（空）" : oldSummary) + "\n\n" +
                "【管理员最近说的】\n" + input;

        try {
            String summary = cleanSummary(aiService.chat(prompt));
            if (summary.isEmpty()) return;

            GroupMemory g = groups.get(guildId);
            if (g == null) return;
            g.summary = summary;
            g.pending.clear();
            save();
            if (logger != null) {
                logger.info("[AI] 群 " + guildId + " 超管立场记忆已由 AI 更新压缩（待归纳 "
                        + pendingSnapshot.size() + " 条 → 摘要）");
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warn("[AI] 超管立场压缩失败: " + e.getMessage());
            }
        }
    }

    /** 清洗 LLM 产出的摘要：剥代码块围栏/旧前缀/标题行，限长。 */
    private static String cleanSummary(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(markdown|md)?\\s*", "");
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        while (s.startsWith(SUMMARY_PREFIX)) s = s.substring(SUMMARY_PREFIX.length());
        while (s.startsWith("[以往观点摘要]")) s = s.substring("[以往观点摘要]".length());
        StringBuilder filtered = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            if (line.trim().startsWith("#")) continue; // 防止 LLM 输出标题破坏 md 结构
            filtered.append(line).append('\n');
        }
        s = filtered.toString().trim();
        if (s.length() > MAX_SUMMARY_CHARS) s = s.substring(0, MAX_SUMMARY_CHARS);
        return s.trim();
    }

    /**
     * 构建"超管观点"上下文，注入到 AI 系统提示中。
     */
    public String buildContext(String guildId) {
        GroupMemory g = groups.get(guildId);
        if (g == null || (g.summary.isEmpty() && g.pending.isEmpty())) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【你的立场依据——群主/管理员说过的话】\n");
        sb.append("你在群里要跟群主/管理员保持一致。如果有人跟群主观点不同，你要站在群主那边。\n");
        sb.append("不要直接说「群主说过」，而是自然地表达同样的立场。\n\n");
        if (!g.summary.isEmpty()) {
            sb.append("群主立场的长期记忆：\n").append(g.summary.trim()).append("\n\n");
        }
        synchronized (g.pending) {
            if (!g.pending.isEmpty()) {
                sb.append("群主最近说的：\n");
                for (String line : g.pending) {
                    sb.append("- ").append(line).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 判断某条消息是否可能跟超管观点冲突。
     */
    public boolean mightConflict(String guildId, String message) {
        if (message == null || message.trim().isEmpty()) return false;
        Set<String> adminWords = extractKeywords(guildId);
        if (adminWords.isEmpty()) return false;
        Set<String> msgWords = tokenize(message);
        int overlap = 0;
        for (String w : msgWords) {
            if (adminWords.contains(w)) overlap++;
        }
        return overlap >= 2;
    }

    private Set<String> extractKeywords(String guildId) {
        Set<String> words = new HashSet<>();
        GroupMemory g = groups.get(guildId);
        if (g != null) {
            words.addAll(tokenize(g.summary));
            synchronized (g.pending) {
                for (String line : g.pending) words.addAll(tokenize(line));
            }
        }
        return words;
    }

    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        String[] parts = text.split("[^\\u4e00-\\u9fa5a-zA-Z0-9]+");
        for (String p : parts) {
            if (p.length() >= 2) tokens.add(p.toLowerCase());
        }
        return tokens;
    }

    // ==================== 持久化（Markdown） ====================

    private void load() {
        loadMd();
        if (legacyFile.exists()) {
            migrateLegacyJson();
        }
    }

    /**
     * 从 admin_stance.md 加载。文件结构：
     * <pre>
     * # 超管立场记忆
     * &lt;!-- 说明注释 --&gt;
     *
     * ## &lt;guildId&gt;
     * 摘要正文（多行 Markdown）
     *
     * ### 待归纳
     * - 原始发言1
     * - 原始发言2
     * </pre>
     */
    private void loadMd() {
        if (!file.exists()) return;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            String currentGuild = null;
            boolean inPending = false;
            StringBuilder summary = null;
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.startsWith("## ")) {
                    flushGroup(currentGuild, summary);
                    currentGuild = line.substring(3).trim();
                    summary = new StringBuilder();
                    inPending = false;
                } else if (currentGuild == null) {
                    continue; // 文件头（标题/说明注释）
                } else if (line.equals(PENDING_HEADING)) {
                    inPending = true;
                } else if (line.startsWith("- ") && inPending) {
                    GroupMemory g = groups.computeIfAbsent(currentGuild, k -> new GroupMemory());
                    String q = line.substring(2).trim();
                    if (!q.isEmpty()) g.pending.add(q);
                } else if (!line.isEmpty() && !line.startsWith("<!--") && !inPending && summary != null) {
                    if (summary.length() > 0) summary.append('\n');
                    summary.append(line);
                }
            }
            flushGroup(currentGuild, summary);
        } catch (Exception ignored) {
        }
    }

    private void flushGroup(String guildId, StringBuilder summary) {
        if (guildId == null) return;
        GroupMemory g = groups.computeIfAbsent(guildId, k -> new GroupMemory());
        if (summary != null) {
            String s = summary.toString().trim();
            if (s.length() > MAX_LOADED_SUMMARY_CHARS) s = s.substring(0, MAX_LOADED_SUMMARY_CHARS);
            g.summary = s;
        }
        synchronized (g.pending) {
            while (g.pending.size() > MAX_PENDING_PER_GROUP) g.pending.remove(0);
        }
    }

    /** 旧 admin_stance.json → md：摘要条目并入摘要，其余进待归纳；完成后删除 json。 */
    private void migrateLegacyJson() {
        try (Reader r = new InputStreamReader(new FileInputStream(legacyFile), StandardCharsets.UTF_8)) {
            Map<String, List<String>> loaded = GSON.fromJson(r,
                    new TypeToken<Map<String, List<String>>>() {}.getType());
            if (loaded != null) {
                for (Map.Entry<String, List<String>> e : loaded.entrySet()) {
                    if (e.getValue() == null) continue;
                    GroupMemory g = groups.computeIfAbsent(e.getKey(), k -> new GroupMemory());
                    for (String item : e.getValue()) {
                        boolean isSummary = item != null && item.startsWith("[以往观点摘要]");
                        String cleaned = stripNestedSummaryPrefix(item);
                        if (cleaned.isEmpty()) continue;
                        if (isSummary) {
                            g.summary = cleaned;
                        } else {
                            synchronized (g.pending) {
                                g.pending.add(cleaned);
                            }
                        }
                    }
                    synchronized (g.pending) {
                        while (g.pending.size() > MAX_PENDING_PER_GROUP) g.pending.remove(0);
                    }
                }
            }
            save();
            Files.deleteIfExists(legacyFile.toPath());
            migratedFromJson = true;
        } catch (Exception ignored) {
        }
    }

    /** 去除嵌套的 [以往观点摘要] 前缀，提取实际内容。 */
    private static String stripNestedSummaryPrefix(String s) {
        if (s == null) return "";
        while (s.startsWith(SUMMARY_PREFIX)) {
            s = s.substring(SUMMARY_PREFIX.length());
        }
        while (s.startsWith("[以往观点摘要]")) {
            s = s.substring("[以往观点摘要]".length());
        }
        return s.trim();
    }

    private void save() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# 超管立场记忆\n\n");
            sb.append("<!-- 本文件由机器人自动维护：管理员的新发言先进「待归纳」，攒够后由 AI 自动压缩合并进摘要；也可手动编辑。 -->\n\n");
            List<String> ids = new ArrayList<>(groups.keySet());
            Collections.sort(ids);
            for (String guildId : ids) {
                GroupMemory g = groups.get(guildId);
                if (g == null) continue;
                sb.append("## ").append(guildId).append("\n\n");
                if (!g.summary.isEmpty()) {
                    sb.append(g.summary).append("\n\n");
                }
                synchronized (g.pending) {
                    if (!g.pending.isEmpty()) {
                        sb.append(PENDING_HEADING).append("\n");
                        for (String q : g.pending) {
                            sb.append("- ").append(q).append("\n");
                        }
                        sb.append("\n");
                    }
                }
            }
            Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
