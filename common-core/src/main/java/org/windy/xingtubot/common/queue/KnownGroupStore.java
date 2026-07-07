package org.windy.xingtubot.common.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 已知群列表：持久化记录机器人「见过」的所有群 group_openid。
 *
 * <p>QQ 官方机器人无法枚举自己加入的群，只能从收到的消息里反推。本仓库在每条群消息进来时
 * 记录其 group_openid，落盘到 {@code known_groups.json}，从而让「推送到全部群（*）」的主动消息
 * 有一份真实的目标群清单可用——而不是只能回退被动队列。
 *
 * <p>单例 + 文件持久化，模式与 {@link PendingMessageQueue} 一致。线程安全。
 */
public final class KnownGroupStore {

    private static final KnownGroupStore INSTANCE = new KnownGroupStore();

    public static KnownGroupStore getInstance() {
        return INSTANCE;
    }

    /** 已知群 openid（保持插入顺序，便于阅读持久化文件）。 */
    private final Set<String> groups = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 持久化文件（null = 不持久化）。 */
    private volatile File persistFile;

    private KnownGroupStore() {
    }

    /**
     * 初始化持久化。应在插件启动时调用，会自动从文件恢复已知群。
     *
     * @param dataDir 插件数据目录
     */
    public void init(File dataDir) {
        if (dataDir == null) return;
        this.persistFile = new File(dataDir, "known_groups.json");
        loadFromDisk();
    }

    /**
     * 记录一个群 openid。新群会触发落盘；已知群直接返回（零开销）。
     *
     * @return true 表示这是首次见到该群
     */
    public boolean record(String groupOpenId) {
        if (groupOpenId == null || groupOpenId.isEmpty()) return false;
        boolean added = groups.add(groupOpenId);
        if (added) flushToDisk();
        return added;
    }

    /** 所有已知群 openid 的快照（不可变副本）。 */
    public Set<String> all() {
        synchronized (groups) {
            return new LinkedHashSet<>(groups);
        }
    }

    public int size() {
        return groups.size();
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    // ==================== 持久化 ====================

    private void flushToDisk() {
        File file = this.persistFile;
        if (file == null) return;
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            synchronized (groups) {
                for (String g : groups) arr.add(g);
            }
            root.add("groups", arr);

            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            if (file.exists()) file.delete();
            tmp.renameTo(file);
        } catch (Exception e) {
            // 持久化失败不影响内存集合
        }
    }

    private void loadFromDisk() {
        File file = this.persistFile;
        if (file == null || !file.exists()) return;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root.has("groups") && root.get("groups").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("groups")) {
                    String g = el.getAsString();
                    if (g != null && !g.isEmpty()) groups.add(g);
                }
            }
        } catch (Exception e) {
            // 解析失败：忽略，下一条群消息会重新填充
        }
    }
}
