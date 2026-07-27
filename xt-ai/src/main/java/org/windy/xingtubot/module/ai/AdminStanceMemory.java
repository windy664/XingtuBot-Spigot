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
 * <p>持久化到 JSON 文件，重启不丢失。每群最多保留 10 条。
 * <p>原始立场超过阈值时，自动调用 LLM 压缩成摘要，节省 system prompt token。
 */
public final class AdminStanceMemory {

    private static final int MAX_PER_GROUP = 10;
    private static final int COMPRESS_THRESHOLD = 5; // 原始条目超过此值触发 LLM 压缩
    private static final Gson GSON = new Gson();
    private static final String SUMMARY_PREFIX = "[以往观点摘要] ";

    private final ConcurrentHashMap<String, List<String>> stances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> pendingCompress = new ConcurrentHashMap<>();
    private final File file;
    private volatile AiService aiService;
    private volatile BotLogger logger;

    public AdminStanceMemory(File dataDir) {
        this.file = new File(dataDir, "admin_stance.json");
        load();
    }

    /** 注入 LLM 服务，启用自动压缩（不注入则只做截断兜底）。 */
    public void setAiService(AiService aiService, BotLogger logger) {
        this.aiService = aiService;
        this.logger = logger;
    }

    /**
     * 记录一条超管发言（自动持久化，攒够阈值异步 LLM 压缩）。
     */
    public void record(String guildId, String content) {
        if (guildId == null || content == null || content.trim().isEmpty()) return;
        List<String> list = stances.computeIfAbsent(guildId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            // 去重
            if (!list.isEmpty() && list.get(list.size() - 1).equals(content.trim())) return;
            list.add(content.trim());
        }
        save();
        // 异步检查是否需要压缩
        maybeCompressAsync(guildId, list);
    }

    /**
     * 原始条目超过阈值时，异步调用 LLM 压缩成摘要。
     * 不阻塞 record() 调用。
     */
    private void maybeCompressAsync(String guildId, List<String> list) {
        int rawCount = countRaw(list);
        if (rawCount <= COMPRESS_THRESHOLD) return;
        if (aiService == null) return; // 无 LLM，跳过
        if (pendingCompress.putIfAbsent(guildId, Boolean.TRUE) != null) return; // 已有压缩在跑

        // 快照当前条目用于异步处理
        List<String> snapshot;
        synchronized (list) {
            snapshot = new ArrayList<>(list);
        }

        new Thread(() -> {
            try {
                doCompress(guildId, snapshot);
            } finally {
                pendingCompress.remove(guildId);
            }
        }, "admin-stance-compress-" + guildId).start();
    }

    private int countRaw(List<String> list) {
        int count = 0;
        for (String item : list) {
            if (!item.startsWith(SUMMARY_PREFIX)) count++;
        }
        return count;
    }

    /**
     * LLM 压缩：把所有条目（旧摘要 + 原始消息）交给 LLM 合并成一句精炼摘要。
     */
    private void doCompress(String guildId, List<String> snapshot) {
        // 构建压缩 prompt
        StringBuilder input = new StringBuilder();
        for (String item : snapshot) {
            input.append("- ").append(item).append("\n");
        }

        String prompt = "以下是管理员在QQ群里说过的话，请用1-2句中文总结他的核心立场和观点，" +
                "保留具体态度和偏好，丢掉无关细节。直接输出摘要，不要加任何前缀。\n\n" + input;

        try {
            String summary = aiService.chat(prompt);
            if (summary == null || summary.trim().isEmpty()) return;

            String compressed = SUMMARY_PREFIX + summary.trim();
            List<String> list = stances.get(guildId);
            if (list == null) return;

            synchronized (list) {
                list.clear();
                list.add(compressed);
            }
            save();
            if (logger != null) {
                logger.info("[AI] 群 " + guildId + " 超管立场已压缩（" + snapshot.size() + "条 → 1条摘要）");
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warn("[AI] 超管立场压缩失败: " + e.getMessage());
            }
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
