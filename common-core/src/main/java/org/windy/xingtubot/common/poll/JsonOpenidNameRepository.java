package org.windy.xingtubot.common.poll;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * OpenID → 昵称 JSON 文件仓库。
 * 单文件存储，读写简单，无外部依赖。
 */
public class JsonOpenidNameRepository implements OpenidNameRepository {

    private static final Gson GSON = new Gson();
    private final File file;
    private final Consumer<String> logger;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public JsonOpenidNameRepository(File file, Consumer<String> logger) {
        this.file = file;
        this.logger = logger;
        // 启动时加载
        cache.putAll(loadFromDisk());
        if (logger != null) logger.accept("[OpenidName] 从 JSON 加载 " + cache.size() + " 条");
    }

    @Override
    public Map<String, String> loadAll() {
        return new HashMap<>(cache);
    }

    @Override
    public void upsert(String openid, String nickname) {
        cache.put(openid, nickname);
        saveToDisk();
    }

    private Map<String, String> loadFromDisk() {
        if (!file.exists()) return new HashMap<>();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<String, String> map = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            if (logger != null) logger.accept("[OpenidName] JSON 加载失败: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private void saveToDisk() {
        try {
            file.getParentFile().mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(cache, w);
            }
        } catch (Exception e) {
            if (logger != null) logger.accept("[OpenidName] JSON 保存失败: " + e.getMessage());
        }
    }
}
