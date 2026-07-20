package org.windy.xingtubot.module.mcsm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.platform.BotLogger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 事件监控：定时轮询实例/节点状态，检测变化并推送通知。
 *
 * <p>支持三种事件（各自独立开关）：
 * <ul>
 *   <li>实例崩溃：运行中(3) → 停止(0)，手动操作窗口内不告警</li>
 *   <li>实例恢复：之前崩溃过的实例重新回到运行中(3)</li>
 *   <li>节点离线：节点从可用变为不可用</li>
 * </ul>
 */
public class McsmMonitor {

    private static final int STATUS_RUNNING = 3;
    private static final int STATUS_STOPPED = 0;
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000; // 5 分钟去重

    private final McsmClient client;
    private final McsmConfig config;
    private final ProactiveSender sender;
    private final BotLogger logger;

    // 实例状态追踪
    private final Map<String, Integer> lastInstStatus = new ConcurrentHashMap<>();
    // 已崩溃的实例 UUID 集合（用于恢复检测）
    private final Set<String> crashedInstances = new CopyOnWriteArraySet<>();
    // 告警冷却
    private final Map<String, Long> lastAlertTime = new ConcurrentHashMap<>();
    // 名称缓存
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();

    // 节点状态追踪
    private final Map<String, Boolean> lastNodeAvailable = new ConcurrentHashMap<>();
    private final Map<String, Long> lastNodeAlertTime = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    // 手动操作标记
    private volatile boolean manualOpFlag = false;
    private volatile long manualOpTimestamp = 0;
    private static final long MANUAL_OP_WINDOW_MS = 30_000;

    public McsmMonitor(McsmClient client, McsmConfig config, ProactiveSender sender, BotLogger logger) {
        this.client = client;
        this.config = config;
        this.sender = sender;
        this.logger = logger;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCSM-Monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 5, config.pollInterval(), TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * 标记一次手动操作（由命令层调用）。
     */
    public void markManualOperation() {
        manualOpFlag = true;
        manualOpTimestamp = System.currentTimeMillis();
    }

    private void poll() {
        try {
            JsonArray nodes = client.getRemoteNodes();
            for (JsonElement el : nodes) {
                JsonObject node = el.getAsJsonObject();
                String daemonId = str(node, "uuid");
                String nodeName = str(node, "remarks");
                if (nodeName.isEmpty()) nodeName = daemonId.substring(0, 8);
                boolean available = node.has("available") && node.get("available").getAsBoolean();

                // ── 节点离线检测 ──
                if (config.notifyNodeOffline()) {
                    checkNodeOffline(daemonId, nodeName, available);
                }

                if (!available) continue; // 节点离线就不查实例了

                // ── 实例状态检测 ──
                try {
                    JsonArray instances = client.listAllInstances(daemonId);
                    for (JsonElement ie : instances) {
                        JsonObject inst = ie.getAsJsonObject();
                        String uuid = str(inst, "instanceUuid");
                        int status = getInt(inst, "status", -1);
                        String nickname = str(inst, "nickname");
                        if (nickname.isEmpty()) nickname = uuid;

                        nameCache.put(uuid, nickname);

                        Integer prev = lastInstStatus.put(uuid, status);

                        // 崩溃检测：运行中(3) → 停止(0)
                        if (config.notifyCrash()
                                && prev != null && prev == STATUS_RUNNING && status == STATUS_STOPPED) {
                            if (!isManualOpWindow() && !isInCooldown("crash:" + uuid)) {
                                lastAlertTime.put("crash:" + uuid, System.currentTimeMillis());
                                crashedInstances.add(uuid);
                                sendAlert("⚠️ 实例崩溃告警", instanceName(nickname),
                                        nodeName, "执行 `msm 启动 " + nickname + "` 重新启动");
                            }
                        }

                        // 恢复检测：之前崩溃过，现在回到运行中(3)
                        if (config.notifyRecovery()
                                && status == STATUS_RUNNING && crashedInstances.remove(uuid)) {
                            if (!isInCooldown("recovery:" + uuid)) {
                                lastAlertTime.put("recovery:" + uuid, System.currentTimeMillis());
                                sendAlert("✅ 实例已恢复", instanceName(nickname),
                                        nodeName, null);
                            }
                        }
                    }
                } catch (McsmClient.McsmException e) {
                    if (logger != null) logger.warn("[MCSM Monitor] 节点 " + nodeName + " 拉取失败: " + e.getMessage());
                }
            }
        } catch (McsmClient.McsmException e) {
            if (logger != null) logger.warn("[MCSM Monitor] 轮询失败: " + e.getMessage());
        }
    }

    // ── 节点离线检测 ──

    private void checkNodeOffline(String daemonId, String nodeName, boolean available) {
        Boolean prev = lastNodeAvailable.put(daemonId, available);
        if (prev != null && prev && !available) {
            // 从可用变为不可用
            if (!isNodeInCooldown(daemonId)) {
                lastNodeAlertTime.put(daemonId, System.currentTimeMillis());
                sendAlert("🔴 节点离线", null, nodeName, null);
            }
        }
    }

    private boolean isNodeInCooldown(String daemonId) {
        Long last = lastNodeAlertTime.get(daemonId);
        return last != null && (System.currentTimeMillis() - last) < ALERT_COOLDOWN_MS;
    }

    // ── 手动操作窗口 ──

    private boolean isManualOpWindow() {
        if (!manualOpFlag) return false;
        if (System.currentTimeMillis() - manualOpTimestamp < MANUAL_OP_WINDOW_MS) return true;
        manualOpFlag = false;
        return false;
    }

    private boolean isInCooldown(String key) {
        Long last = lastAlertTime.get(key);
        return last != null && (System.currentTimeMillis() - last) < ALERT_COOLDOWN_MS;
    }

    // ── 推送 ──

    private void sendAlert(String title, String instanceName, String nodeName, String hint) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(title).append("\n\n");
        if (instanceName != null) sb.append("**实例** `").append(instanceName).append("`\n");
        sb.append("**节点** `").append(nodeName).append("`\n");
        sb.append("**时间** ").append(time).append("\n");
        if (hint != null) sb.append("\n> ").append(hint).append("\n");

        String markdown = sb.toString();
        pushToGroups(markdown);
        if (logger != null) logger.info("[MCSM Monitor] " + title + ": " +
                (instanceName != null ? instanceName + " @ " : "") + nodeName);
    }

    private void pushToGroups(String markdown) {
        if (sender == null || !sender.isReady()) return;
        List<String> groups = config.notifyGroups();
        if (groups == null || groups.isEmpty()) {
            if (logger != null) logger.warn("[MCSM Monitor] 未配置 notify-groups，无法推送通知");
            return;
        }
        for (String group : groups) {
            try {
                sender.sendGroupMarkdown(group, markdown);
            } catch (Exception e) {
                if (logger != null) logger.warn("[MCSM Monitor] 推送到群 " + group + " 失败: " + e.getMessage());
            }
        }
    }

    private static String instanceName(String nickname) {
        return nickname;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : "";
    }

    private static int getInt(JsonObject o, String key, int def) {
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) { try { return el.getAsInt(); } catch (Exception ignored) {} }
        return def;
    }
}
