package org.windy.xingtubot.module.mcsm.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.windy.xingtubot.module.mcsm.McsmClient;
import org.windy.xingtubot.module.mcsm.McsmConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例名 → (uuid, daemonId) 解析器。
 *
 * <p>支持按实例昵称查找，缓存 60 秒过期自动刷新。
 */
public class InstanceResolver {

    private final McsmClient client;
    private final McsmConfig config;

    private final Map<String, Ref> cache = new ConcurrentHashMap<>();
    private volatile long lastRefresh = 0;
    private static final long CACHE_TTL_MS = 60_000;

    public InstanceResolver(McsmClient client, McsmConfig config) {
        this.client = client;
        this.config = config;
    }

    /**
     * 按实例名查找。返回 null 表示未找到。
     */
    public Ref resolve(String name) {
        if (name == null || name.isEmpty()) return null;
        refreshIfNeeded();
        // 精确匹配
        Ref ref = cache.get(name.toLowerCase());
        if (ref != null) return ref;
        // config 别名
        for (Map.Entry<String, String> entry : config.aliases().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                // 需要从缓存找到对应的 daemonId
                for (Ref r : cache.values()) {
                    if (r.uuid.equals(entry.getKey())) return r;
                }
            }
        }
        return null;
    }

    /**
     * 获取所有已知实例名（用于模糊匹配建议）。
     */
    public List<String> allNames() {
        refreshIfNeeded();
        return new ArrayList<>(cache.keySet());
    }

    /**
     * 查找相似名称（用于"未找到"时的建议）。
     */
    public List<String> suggest(String name) {
        if (name == null || name.isEmpty()) return java.util.Collections.emptyList();
        String lower = name.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String key : cache.keySet()) {
            if (key.contains(lower) || lower.contains(key)) {
                result.add(key);
            }
        }
        return result.size() > 3 ? result.subList(0, 3) : result;
    }

    /**
     * 强制刷新缓存。
     */
    public void refresh() {
        lastRefresh = 0;
        refreshIfNeeded();
    }

    private void refreshIfNeeded() {
        if (System.currentTimeMillis() - lastRefresh < CACHE_TTL_MS) return;
        synchronized (this) {
            if (System.currentTimeMillis() - lastRefresh < CACHE_TTL_MS) return;
            doRefresh();
            lastRefresh = System.currentTimeMillis();
        }
    }

    private void doRefresh() {
        Map<String, Ref> newCache = new LinkedHashMap<>();
        try {
            JsonArray nodes = client.getRemoteNodes();
            for (JsonElement el : nodes) {
                JsonObject node = el.getAsJsonObject();
                String daemonId = str(node, "uuid");
                try {
                    JsonArray instances = client.listAllInstances(daemonId);
                    for (JsonElement ie : instances) {
                        JsonObject inst = ie.getAsJsonObject();
                        String uuid = str(inst, "instanceUuid");
                        // MCSM API 昵称在 config.nickname，不在顶层
                        String nickname = "";
                        JsonObject cfg = inst.getAsJsonObject("config");
                        if (cfg != null) nickname = str(cfg, "nickname");
                        if (nickname.isEmpty()) nickname = str(inst, "nickname");
                        if (nickname.isEmpty()) nickname = uuid;
                        String key = nickname.toLowerCase();
                        newCache.put(key, new Ref(uuid, daemonId, nickname));
                    }
                } catch (McsmClient.McsmException ignored) {
                }
            }
        } catch (McsmClient.McsmException ignored) {
        }
        cache.clear();
        cache.putAll(newCache);
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : "";
    }

    /**
     * 实例引用：UUID + 节点 ID + 显示名。
     */
    public static final class Ref {
        public final String uuid;
        public final String daemonId;
        public final String displayName;

        public Ref(String uuid, String daemonId, String displayName) {
            this.uuid = uuid;
            this.daemonId = daemonId;
            this.displayName = displayName;
        }
    }
}
