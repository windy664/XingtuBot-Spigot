package org.windy.xingtubot.velocity;

import org.windy.xingtubot.common.config.BotConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 YAML 文件实现 common 的 BotConfig。
 * 首次启动时从 jar 内的默认 config.yml 释放到数据目录。
 */
public class VelocityConfig implements BotConfig {
    private final Map<String, Object> data;

    @SuppressWarnings("unchecked")
    public VelocityConfig(Path dataDir) {
        Map<String, Object> loaded = new HashMap<>();
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("config.yml");
            if (!Files.exists(file)) {
                // 与 Spigot 端共用同一份 config.yml（合并 jar 内只有这一份）
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    Object o = new Yaml().load(in);
                    if (o instanceof Map) {
                        loaded = (Map<String, Object>) o;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        this.data = loaded;
    }

    @Override
    public String getString(String path, String def) {
        Object v = data.get(path);
        return v != null ? String.valueOf(v) : def;
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object v = data.get(path);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @Override
    public int getInt(String path, int def) {
        Object v = data.get(path);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    @Override
    public List<String> getStringList(String path) {
        Object v = data.get(path);
        List<String> result = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) result.add(String.valueOf(o));
        }
        return result;
    }
}
