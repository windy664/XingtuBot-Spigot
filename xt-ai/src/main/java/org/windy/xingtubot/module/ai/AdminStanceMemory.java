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
     * 压缩：超过 MAX_RAW_RECENT 条时，旧消息合并成一条摘要。
     * 例如 8 条 → 1 条摘要 + 7 条最近（但总数不超过 MAX_PER_GROUP）。
     */
    private void compress(List<String> list) {
        while (list.size() > MAX_PER_GROUP) {
            // 需要压缩：把前 (size - MAX_RAW_RECENT + 1) 条合并
            int mergeCount = list.size() - MAX_RAW_RECENT + 1;
            if (mergeCount <= 0) break;

            StringBuilder merged = new StringBuilder("[以往观点摘要] ");
            for (int i = 0; i < mergeCount; i++) {
                if (i > 0) merged.append("｜");
                merged.append(list.get(i));
            }
            // 移除被合并的，插入摘要
            for (int i = 0; i < mergeCount; i++) {
                list.remove(0);
            }
            list.add(0, merged.toString());
        }
    }

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
                for (Map.Entry<String, List<String>> e : loaded.entrySet()) {
                    List<String> list = Collections.synchronizedList(new ArrayList<>(e.getValue()));
                    // 裁剪到上限
                    while (list.size() > MAX_PER_GROUP) list.remove(0);
                    stances.put(e.getKey(), list);
                }
            }
        } catch (Exception ignored) {
        }
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
