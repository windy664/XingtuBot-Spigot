package org.windy.xingtubot.common.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 通用挂起消息队列：解决 QQ 机器人无法主动推送的问题。
 *
 * <p>支持两种投递模式：
 * <ul>
 *   <li>全局消息：任何群都可以消费（向后兼容）</li>
 *   <li>定向消息：只有指定 groupOpenId 的群可以消费</li>
 * </ul>
 *
 * <p>支持文件持久化：调用 {@link #init(File)} 后，消息会写入磁盘，
 * 服务器重启后自动恢复未消费的消息。
 *
 * <p>线程安全，支持多生产者（定时器线程）单消费者（消息分发线程）。
 */
public class PendingMessageQueue {

    private static final PendingMessageQueue INSTANCE = new PendingMessageQueue();

    public static PendingMessageQueue getInstance() {
        return INSTANCE;
    }

    /** 全局消息队列（任何群都可以消费） */
    private final Queue<String> globalQueue = new ConcurrentLinkedQueue<>();

    /** 定向消息队列（key = targetGroupOpenId） */
    private final Map<String, Queue<String>> targetedQueue = new ConcurrentHashMap<>();

    /** 持久化文件（null = 不持久化） */
    private volatile File persistFile;

    private PendingMessageQueue() {
    }

    /**
     * 初始化持久化。应在插件启动时调用。
     * 会自动从文件恢复未消费的消息。
     *
     * @param dataDir 插件数据目录（如 plugins/XingtuBot/）
     */
    public void init(File dataDir) {
        if (dataDir == null) return;
        this.persistFile = new File(dataDir, "pending_messages.json");
        loadFromDisk();
    }

    // ==================== 投递 ====================

    /**
     * 投递一条全局挂起消息（任意线程调用）。
     * 任何群在下次收到消息时都可以消费。
     */
    public void offer(String message) {
        if (message != null && !message.isEmpty()) {
            globalQueue.offer(message);
            flushToDisk();
        }
    }

    /**
     * 投递一条定向挂起消息（任意线程调用）。
     * 只有指定 groupOpenId 的群在下次收到消息时可以消费。
     *
     * @param targetGroupOpenId 目标群的 group_openid
     * @param message           消息内容
     */
    public void offer(String targetGroupOpenId, String message) {
        if (targetGroupOpenId == null || targetGroupOpenId.isEmpty()) {
            offer(message);
            return;
        }
        if (message == null || message.isEmpty()) return;
        targetedQueue.computeIfAbsent(targetGroupOpenId, k -> new ConcurrentLinkedQueue<>())
                .offer(message);
        flushToDisk();
    }

    // ==================== 消费 ====================

    /** 是否有挂起消息（全局或任意定向）。 */
    public boolean hasPending() {
        if (!globalQueue.isEmpty()) return true;
        for (Queue<String> q : targetedQueue.values()) {
            if (!q.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 按群 ID 消费挂起消息：取出属于该群的定向消息 + 所有全局消息，合并为一条返回。
     * 取完即清。无消息返回 null。
     *
     * @param groupOpenId 当前发起消息的群 openid
     */
    public String drainForGroup(String groupOpenId) {
        List<String> msgs = new ArrayList<>();

        // ① 取定向消息（如果指定了群 ID）
        if (groupOpenId != null && !groupOpenId.isEmpty()) {
            Queue<String> targeted = targetedQueue.remove(groupOpenId);
            if (targeted != null) {
                String msg;
                while ((msg = targeted.poll()) != null) {
                    msgs.add(msg);
                }
            }
        }

        // ② 取全局消息
        String msg;
        while ((msg = globalQueue.poll()) != null) {
            msgs.add(msg);
        }

        if (msgs.isEmpty()) return null;

        // 有消费，更新持久化
        flushToDisk();

        if (msgs.size() == 1) return msgs.get(0);

        // 多条合并
        StringBuilder sb = new StringBuilder();
        sb.append("📢 你有 ").append(msgs.size()).append(" 条待推送消息\n");
        sb.append("══════════════\n");
        for (int i = 0; i < msgs.size(); i++) {
            if (i > 0) sb.append("\n────────────\n");
            sb.append(msgs.get(i));
        }
        return sb.toString();
    }

    /**
     * 取出所有挂起消息（全局 + 所有定向），合并为一条返回。
     * 向后兼容旧代码。
     *
     * @deprecated 请使用 {@link #drainForGroup(String)} 按群消费
     */
    @Deprecated
    public String drainAll() {
        List<String> msgs = new ArrayList<>();

        for (Map.Entry<String, Queue<String>> entry : targetedQueue.entrySet()) {
            Queue<String> q = entry.getValue();
            String msg;
            while ((msg = q.poll()) != null) {
                msgs.add(msg);
            }
        }

        String msg;
        while ((msg = globalQueue.poll()) != null) {
            msgs.add(msg);
        }

        if (msgs.isEmpty()) return null;

        flushToDisk();

        if (msgs.size() == 1) return msgs.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("📢 你有 ").append(msgs.size()).append(" 条待推送消息\n");
        sb.append("══════════════\n");
        for (int i = 0; i < msgs.size(); i++) {
            if (i > 0) sb.append("\n────────────\n");
            sb.append(msgs.get(i));
        }
        return sb.toString();
    }

    /** 清空所有队列（测试/重置用）。 */
    public void clear() {
        globalQueue.clear();
        targetedQueue.clear();
        flushToDisk();
    }

    // ==================== 持久化 ====================

    /** 将当前队列状态写入磁盘（JSON 格式）。 */
    private void flushToDisk() {
        File file = this.persistFile;
        if (file == null) return;

        try {
            JsonObject root = new JsonObject();
            JsonArray globals = new JsonArray();
            for (String m : globalQueue) {
                globals.add(m);
            }
            root.add("global", globals);

            JsonArray targeted = new JsonArray();
            for (Map.Entry<String, Queue<String>> entry : targetedQueue.entrySet()) {
                for (String m : entry.getValue()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("group", entry.getKey());
                    item.addProperty("msg", m);
                    targeted.add(item);
                }
            }
            root.add("targeted", targeted);

            // 先写临时文件再重命名，防止写一半崩溃导致文件损坏
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            // 原子替换
            if (file.exists()) file.delete();
            tmp.renameTo(file);
        } catch (IOException e) {
            // 持久化失败不影响内存队列
        }
    }

    /** 从磁盘恢复队列。 */
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

            // 恢复全局消息
            if (root.has("global") && root.get("global").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("global")) {
                    globalQueue.offer(el.getAsString());
                }
            }

            // 恢复定向消息
            if (root.has("targeted") && root.get("targeted").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("targeted")) {
                    JsonObject item = el.getAsJsonObject();
                    String group = item.has("group") ? item.get("group").getAsString() : "";
                    String msg = item.has("msg") ? item.get("msg").getAsString() : "";
                    if (!msg.isEmpty()) {
                        if (group.isEmpty()) {
                            globalQueue.offer(msg);
                        } else {
                            targetedQueue.computeIfAbsent(group, k -> new ConcurrentLinkedQueue<>())
                                    .offer(msg);
                        }
                    }
                }
            }

            // 恢复完成后删除文件（避免重复消费）
            int total = globalQueue.size();
            for (Queue<String> q : targetedQueue.values()) total += q.size();
            if (total > 0) {
                // 有未消费消息，保留文件（下次 drain 后会清除）
            } else {
                file.delete();
            }
        } catch (Exception e) {
            // 解析失败，删除损坏的文件
            file.delete();
        }
    }
}
