package org.windy.xingtubot.module.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 用量统计与日配额。
 *
 * <h3>为什么要按天而不是按小时</h3>
 * 小时预算防的是"刷屏"，日配额防的是"成本失控"——一天累计烧掉几百块不是
 * 小时上限能拦住的（每小时的 60 次都合法）。两者都得有。
 *
 * <p>按<b>北京时间</b>自然日分桶，跟运营者看账的直觉一致。
 *
 * <p>统计落盘到 {@code ai_usage.json}，这样重启服务器不会把当日已用的额度清零——
 * 否则「重启一次就又能刷一轮」等于没有日配额。
 */
final class AiUsage {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Gson GSON = new Gson();

    /** 一次统计桶：调用数 + token 数。 */
    static final class Bucket {
        int calls;
        long promptTokens;
        long completionTokens;
        long totalTokens;

        void add(int prompt, int completion, int total) {
            calls++;
            promptTokens += prompt;
            completionTokens += completion;
            totalTokens += total > 0 ? total : (prompt + completion);
        }
    }

    private final File file;
    /** 当日总用量。 */
    private final Bucket today = new Bucket();
    /** 按群分桶。 */
    private final ConcurrentHashMap<String, Bucket> byGroup = new ConcurrentHashMap<>();
    /** 当前统计日（北京时间自然日）。 */
    private volatile String day = LocalDate.now(ZONE).toString();

    AiUsage(File dataDir) {
        this.file = new File(dataDir, "ai_usage.json");
        load();
    }

    /** 记一次调用。 */
    void record(String guildId, int promptTokens, int completionTokens, int totalTokens) {
        rollDay();
        today.add(promptTokens, completionTokens, totalTokens);
        if (guildId != null) {
            byGroup.computeIfAbsent(guildId, k -> new Bucket()).add(promptTokens, completionTokens, totalTokens);
        }
    }

    /** 换日就清零（跨自然日）。 */
    private void rollDay() {
        String now = LocalDate.now(ZONE).toString();
        if (!now.equals(day)) {
            synchronized (this) {
                if (!now.equals(day)) {
                    day = now;
                    today.calls = 0;
                    today.promptTokens = 0;
                    today.completionTokens = 0;
                    today.totalTokens = 0;
                    byGroup.clear();
                    save();
                }
            }
        }
    }

    /** 当日总调用次数。 */
    int callsToday() {
        rollDay();
        return today.calls;
    }

    /** 当日总 token 数。 */
    long tokensToday() {
        rollDay();
        return today.totalTokens;
    }

    /** 是否已经超过当日调用上限（max <= 0 表示不限）。 */
    boolean overCallBudget(int max) {
        if (max <= 0) return false;
        rollDay();
        return today.calls >= max;
    }

    /** 是否已经超过当日 token 预算（max <= 0 表示不限）。 */
    boolean overTokenBudget(long max) {
        if (max <= 0) return false;
        rollDay();
        return today.totalTokens >= max;
    }

    /** 用量报告（给人看）。 */
    String render() {
        rollDay();
        StringBuilder sb = new StringBuilder();
        sb.append("📊 AI 用量（").append(day).append(" 北京时间）\n");
        sb.append("调用次数：").append(today.calls).append(" 次\n");
        sb.append("Token：").append(today.totalTokens)
          .append("（输入 ").append(today.promptTokens)
          .append(" / 输出 ").append(today.completionTokens).append("）\n");
        if (!byGroup.isEmpty()) {
            sb.append("—— 分群 ——\n");
            Map<String, Bucket> sorted = new LinkedHashMap<>();
            byGroup.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().totalTokens, a.getValue().totalTokens))
                    .limit(10)
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            for (Map.Entry<String, Bucket> e : sorted.entrySet()) {
                sb.append("  ").append(shortId(e.getKey()))
                  .append("：").append(e.getValue().calls).append(" 次 / ")
                  .append(e.getValue().totalTokens).append(" token\n");
            }
        }
        return sb.toString().trim();
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() <= 8 ? id : id.substring(0, 8) + "…";
    }

    // ==================== 持久化 ====================

    private void load() {
        if (!file.exists()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, Object> loaded = GSON.fromJson(r, new TypeToken<Map<String, Object>>() {}.getType());
            if (loaded == null) return;
            Object d = loaded.get("day");
            if (d != null) day = String.valueOf(d);
            // 只恢复当天的统计；跨天就当空
            if (!LocalDate.now(ZONE).toString().equals(day)) return;
            today.calls = intOf(loaded.get("calls"));
            today.promptTokens = longOf(loaded.get("promptTokens"));
            today.completionTokens = longOf(loaded.get("completionTokens"));
            today.totalTokens = longOf(loaded.get("totalTokens"));
            Object groups = loaded.get("groups");
            if (groups instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) groups).entrySet()) {
                    if (!(e.getValue() instanceof Map)) continue;
                    Map<?, ?> g = (Map<?, ?>) e.getValue();
                    Bucket b = new Bucket();
                    b.calls = intOf(g.get("calls"));
                    b.promptTokens = longOf(g.get("promptTokens"));
                    b.completionTokens = longOf(g.get("completionTokens"));
                    b.totalTokens = longOf(g.get("totalTokens"));
                    byGroup.put(String.valueOf(e.getKey()), b);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 落盘（调用方按需触发，比如每分钟或关服时）。 */
    void save() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("day", day);
            out.put("calls", today.calls);
            out.put("promptTokens", today.promptTokens);
            out.put("completionTokens", today.completionTokens);
            out.put("totalTokens", today.totalTokens);
            Map<String, Object> groups = new LinkedHashMap<>();
            for (Map.Entry<String, Bucket> e : byGroup.entrySet()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("calls", e.getValue().calls);
                g.put("promptTokens", e.getValue().promptTokens);
                g.put("completionTokens", e.getValue().completionTokens);
                g.put("totalTokens", e.getValue().totalTokens);
                groups.put(e.getKey(), g);
            }
            out.put("groups", groups);
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(out, w);
            }
        } catch (Exception ignored) {
        }
    }

    private static int intOf(Object o) {
        if (o == null) return 0;
        try {
            return o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long longOf(Object o) {
        if (o == null) return 0;
        try {
            return o instanceof Number ? ((Number) o).longValue() : Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
