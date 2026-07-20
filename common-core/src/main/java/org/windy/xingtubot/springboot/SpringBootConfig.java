package org.windy.xingtubot.springboot;

import org.windy.xingtubot.common.config.BotConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import org.yaml.snakeyaml.Yaml;

/**
 * Spring Boot 配置适配：读取 {@code dataDir/config.yml}，实现 {@link BotConfig}。
 *
 * <p>逻辑与 {@link org.windy.xingtubot.common.config.YamlBotConfig} 一致，
 * 但不含 Bukkit/Velocity 的插件 classloader 释放逻辑——Spring Boot 通过
 * {@code application.yml} 配置 {@code xingtubot.data-dir} 指定数据目录。
 */
public class SpringBootConfig implements BotConfig {

    private final File dataDir;
    private final Map<String, Object> data;
    private final Set<String> dirtyKeys = new HashSet<>();

    @SuppressWarnings("unchecked")
    public SpringBootConfig(File dataDir) {
        this.dataDir = dataDir;
        Map<String, Object> loaded = new HashMap<>();
        try {
            Files.createDirectories(dataDir.toPath());
            java.nio.file.Path file = dataDir.toPath().resolve("config.yml");
            if (!Files.exists(file)) {
                // 从 classpath 释放默认 config.yml
                try (InputStream in = SpringBootConfig.class.getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    Object o = new Yaml().load(in);
                    if (o instanceof Map) loaded = (Map<String, Object>) o;
                }
            }
        } catch (IOException ignored) {
        }
        this.data = loaded;
    }

    /** 设置一个配置值（内存中），并标记为待写回。 */
    public void set(String path, Object value) {
        data.put(path, value);
        dirtyKeys.add(path);
    }

    /**
     * 将 set() 改动过的标量键写回 config.yml。
     * 按行就地替换，保留注释和排版；找不到的追加到末尾。
     */
    public void save() {
        if (dirtyKeys.isEmpty()) return;
        try {
            java.nio.file.Path file = dataDir.toPath().resolve("config.yml");
            if (!Files.exists(file)) {
                Files.write(file, new Yaml().dump(data).getBytes(StandardCharsets.UTF_8));
                dirtyKeys.clear();
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Set<String> remaining = new HashSet<>(dirtyKeys);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isEmpty() || line.startsWith(" ") || line.startsWith("\t")) continue;
                if (line.trim().startsWith("#")) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                if (!remaining.contains(key)) continue;
                String scalar = serializeScalar(data.get(key));
                if (scalar == null) { remaining.remove(key); continue; }
                lines.set(i, key + ": " + scalar);
                remaining.remove(key);
            }
            StringBuilder out = new StringBuilder();
            for (String l : lines) out.append(l).append("\n");
            StringBuilder appended = new StringBuilder();
            for (String key : remaining) {
                String scalar = serializeScalar(data.get(key));
                if (scalar != null) appended.append(key).append(": ").append(scalar).append("\n");
            }
            if (appended.length() > 0) {
                out.append("\n##### 程序写回时补充的配置项 #####\n").append(appended);
            }
            Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
            dirtyKeys.clear();
        } catch (Exception ignored) {
        }
    }

    private static String serializeScalar(Object v) {
        if (v == null) return "\"\"";
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        if (v instanceof String) {
            String s = ((String) v).replace("\\", "\\\\").replace("\"", "\\\"");
            return "\"" + s + "\"";
        }
        return null;
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

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, String> getStringMap(String path) {
        Object v = data.get(path);
        Map<String, String> result = new HashMap<>();
        if (v instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                result.put(e.getKey().toLowerCase(), String.valueOf(e.getValue()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> getStringMapList(String path) {
        Object v = data.get(path);
        if (!(v instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) v) {
            if (item instanceof Map) {
                Map<String, Object> entry = new HashMap<>();
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) item).entrySet()) {
                    entry.put(String.valueOf(e.getKey()), e.getValue());
                }
                result.add(entry);
            }
        }
        return result;
    }
}
