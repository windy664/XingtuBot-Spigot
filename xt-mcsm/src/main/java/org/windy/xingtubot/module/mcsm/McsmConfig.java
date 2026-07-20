package org.windy.xingtubot.module.mcsm;

import org.windy.xingtubot.common.config.BotConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCSM 配置封装。
 */
public final class McsmConfig {

    private final String panelUrl;
    private final String apiKey;
    private final List<NodeEntry> nodes;
    private final int pollInterval;
    private final List<String> notifyGroups;
    private final boolean sanitize;
    private final Map<String, String> aliases;
    private final boolean notifyCrash;
    private final boolean notifyRecovery;
    private final boolean notifyNodeOffline;

    public McsmConfig(BotConfig config) {
        this.panelUrl = stripTrailingSlash(config.getString("panel-url", "http://localhost:23333"));
        this.apiKey = config.getString("api-key", "");
        this.nodes = parseNodes(config.getStringMapList("nodes"));
        this.pollInterval = config.getInt("poll-interval", 30);
        this.notifyGroups = config.getStringList("notify-groups");
        this.sanitize = config.getBoolean("sanitize", true);
        this.aliases = config.getStringMap("aliases");
        this.notifyCrash = config.getBoolean("notify-crash", true);
        this.notifyRecovery = config.getBoolean("notify-recovery", true);
        this.notifyNodeOffline = config.getBoolean("notify-node-offline", true);
    }

    public String panelUrl() { return panelUrl; }
    public String apiKey() { return apiKey; }
    public List<NodeEntry> nodes() { return nodes; }
    public int pollInterval() { return pollInterval; }
    public List<String> notifyGroups() { return notifyGroups; }
    public boolean sanitize() { return sanitize; }
    public Map<String, String> aliases() { return aliases; }
    public boolean notifyCrash() { return notifyCrash; }
    public boolean notifyRecovery() { return notifyRecovery; }
    public boolean notifyNodeOffline() { return notifyNodeOffline; }

    /** 是否手动配置了节点列表。 */
    public boolean hasNodes() {
        return !nodes.isEmpty();
    }

    /** 节点配置条目。 */
    public static final class NodeEntry {
        private final String id;
        private final String name;
        private final String apiKey;

        public NodeEntry(String id, String name, String apiKey) {
            this.id = id;
            this.name = name;
            this.apiKey = apiKey;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String apiKey() { return apiKey; }
    }

    private static List<NodeEntry> parseNodes(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<NodeEntry> result = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            String id = str(entry.get("id"));
            String name = str(entry.get("name"));
            String key = str(entry.get("api-key"));
            if (!id.isEmpty()) {
                result.add(new NodeEntry(id, name, key));
            }
        }
        return result;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
