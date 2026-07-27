package org.windy.xingtubot.module.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 超管观点长期记忆：记录超管在群聊中表达的立场和观点，
 * 用于让 AI 在群聊中自然地站在超管一方。
 *
 * <p>持久化到 JSON 文件，重启不丢失。每群最多保留 10 条。
 */
public final class AdminStanceMemory {

    private static final int MAX_PER_GROUP = 10;
    private static final int MAX_RAW_RECENT = 5;
    private static final Gson GSON = new Gson();

    private final ConcurrentHashMap<String, List<String>> stances = new ConcurrentHashMap<>();
    private final File file;

    public AdminStanceMemory(File dataDir) {
        this.file = new File(dataDir, "admin_stance.json");
        load();
    }

    /**
     * 记录一条超管发言（自动持久化，旧消息自动压缩）。
     */
    public void record(String guildId, String content) {
        if (guildId == null || content == null || content.trim().isEmpty()) return;
        List<String> list = stances.computeIfAbsent(guildId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            // 去重
            if (!list.isEmpty() && list.get(list.size() - 1).equals(content.trim())) return;
            list.add(content.trim());
            compress(list);
        }
        save();
    }

    /**
     * 压缩：超过 MAX_PER_GROUP 时，旧的原始消息合并成一条摘要。
     * 已有的摘要条目不再参与合并（避免嵌套前缀爆炸）。
     */
    private void compress(List<String> list) {
        // 分离摘要和原始消息
        List<String> summaries = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        for (String item : list) {
            if (item.startsWith(SUMMARY_PREFIX)) {
                summaries.add(item);
            } else {
                raw.add(item);
            }
        }

        // 原始消息超限 → 旧的合并成一条摘要
        if (raw.size() > MAX_RAW_RECENT) {
            int mergeCount = raw.size() - MAX_RAW_RECENT + 1;
            StringBuilder merged = new StringBuilder(SUMMARY_PREFIX);
            for (int i = 0; i < mergeCount; i++) {
                if (i > 0) merged.append("｜");
                merged.append(raw.get(i));
            }
            // 保留未合并的原始消息
            List<String> kept = new ArrayList<>(raw.subList(mergeCount, raw.size()));
            // 旧摘要 + 新合并摘要（最多保留1条摘要）+ 未合并的原始消息
            summaries.add(merged.toString());
            if (summaries.size() > 1) {
                // 多条摘要合并成一条
                StringBuilder allSum = new StringBuilder(SUMMARY_PREFIX);
                for (int i = 0; i < summaries.size(); i++) {
                    String s = summaries.get(i);
                    if (i > 0) allSum.append("｜");
                    // 去掉已有前缀避免嵌套
                    allSum.append(s.startsWith(SUMMARY_PREFIX)
                            ? s.substring(SUMMARY_PREFIX.length()) : s);
                }
                summaries.clear();
                summaries.add(allSum.toString());
            }
            raw = kept;
        }

        // 重组：摘要在前，原始消息在后
        list.clear();
        list.addAll(summaries);
        list.addAll(raw);

        // 最终裁剪
        while (list.size() > MAX_PER_GROUP) list.remove(list.size() - 1);
    }

    private static final String SUMMARY_PREFIX = "[以往观点摘要] ";

    /**
     * 构建"超管观点"上下文，注入到 AI 系统提示中。
     */
    public String buildContext(String guildId) {
        List<String> list = stances.get(guildId);
        if (list == null || list.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【你的立场依据——群主/管理员说过的话】\n");
        sb.append("你在群里要跟群主/管理员保持一致。如果有人跟群主观点不同，你要站在群主那边。\n");
        sb.append("不要直接说「群主说过」，而是自然地表达同样的立场。\n\n");
        sb.append("群主最近说的：\n");
        synchronized (list) {
            for (String line : list) {
                sb.append("- ").append(line).append("\n");
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
        List<String> list = stances.get(guildId);
        if (list != null) {
            synchronized (list) {
                for (String line : list) words.addAll(tokenize(line));
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

    // ==================== 持久化 ====================

    private void load() {
        if (!file.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<String, List<String>> loaded = GSON.fromJson(r,
                    new TypeToken<Map<String, List<String>>>() {}.getType());
            if (loaded != null) {
                boolean dirty = false;
                for (Map.Entry<String, List<String>> e : loaded.entrySet()) {
                    List<String> list = Collections.synchronizedList(new ArrayList<>());
                    for (String item : e.getValue()) {
                        String cleaned = stripNestedSummaryPrefix(item);
                        if (cleaned != item) dirty = true;
                        if (!cleaned.isEmpty()) list.add(cleaned);
                    }
                    while (list.size() > MAX_PER_GROUP) list.remove(0);
                    stances.put(e.getKey(), list);
                }
                if (dirty) save(); // 修复后回写磁盘
            }
        } catch (Exception ignored) {
        }
    }

    /** 去除嵌套的 [以往观点摘要] 前缀，提取实际内容。 */
    private static String stripNestedSummaryPrefix(String s) {
        if (s == null) return "";
        // 反复剥掉 [以往观点摘要] 前缀直到没有
        while (s.startsWith(SUMMARY_PREFIX)) {
            s = s.substring(SUMMARY_PREFIX.length());
        }
        // 剥掉残余的嵌套前缀（没有空格分隔的情况）
        while (s.startsWith("[以往观点摘要]")) {
            s = s.substring("[以往观点摘要]".length());
        }
        return s.trim();
    }

    private void save() {
        try {
            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : stances.entrySet()) {
                synchronized (e.getValue()) {
                    snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
            Files.write(file.toPath(), GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
