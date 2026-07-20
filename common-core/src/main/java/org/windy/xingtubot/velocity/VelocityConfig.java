package org.windy.xingtubot.velocity;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.config.ConfigMerger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 YAML 文件实现 common 的 BotConfig。
 * 首次启动时从 jar 内的默认 config.yml 释放到数据目录。
 */
public class VelocityConfig implements BotConfig {
    private final Path dataDir;
    private final Map<String, Object> data;
    // 经 set() 改动过、待写回的顶层键。save() 只就地替换这些键所在的行，
    // 其余行（含注释/排版/列表块）原样保留，避免整文件 dump 冲掉注释。
    private final java.util.Set<String> dirtyKeys = new java.util.HashSet<>();

    @SuppressWarnings("unchecked")
    public VelocityConfig(Path dataDir) {
        this.dataDir = dataDir;
        Map<String, Object> loaded = new HashMap<>();
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("config.yml");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            // 合并模板：补缺失键(含嵌套)+注释废弃键+保留注释；仅结构有差异时才重写
            ConfigMerger.sync(
                    file, getClass().getClassLoader(), "config.yml");
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

    /** 设置一个配置值（内存中），并标记为待写回。 */
    public void set(String path, Object value) {
        data.put(path, value);
        dirtyKeys.add(path);
    }

    /**
     * 将 set() 改动过的键写回 config.yml。
     *
     * <p>采用「按行就地替换」而非整文件 dump：只重写被改动的顶层<b>标量</b>键所在的那一行，
     * 其余行（注释、空行、排版、列表/映射块）原样保留，从而不会冲掉模板里的注释。
     * 文件里找不到的标量脏键追加到末尾；非标量（列表/映射）脏键不就地处理（本类只用于写凭据等标量）。
     */
    public void save() {
        if (dirtyKeys.isEmpty()) return;
        try {
            Path file = dataDir.resolve("config.yml");
            if (!Files.exists(file)) {
                // 兜底：文件不存在（理论上构造时已释放），退回整文件 dump。
                Files.write(file, new Yaml().dump(data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                dirtyKeys.clear();
                return;
            }
            List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
            java.util.Set<String> remaining = new java.util.HashSet<>(dirtyKeys);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                // 只认顶层键行：行首非空白、非注释、含冒号
                if (line.isEmpty() || line.startsWith(" ") || line.startsWith("\t")) continue;
                if (line.trim().startsWith("#")) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                if (!remaining.contains(key)) continue;
                String scalar = serializeScalar(data.get(key));
                if (scalar == null) { remaining.remove(key); continue; } // 非标量：原文不动
                lines.set(i, key + ": " + scalar);
                remaining.remove(key);
            }
            StringBuilder out = new StringBuilder();
            for (String l : lines) out.append(l).append("\n");
            // 脏键里文件中没有的标量键，追加到末尾
            StringBuilder appended = new StringBuilder();
            for (String key : remaining) {
                String scalar = serializeScalar(data.get(key));
                if (scalar != null) appended.append(key).append(": ").append(scalar).append("\n");
            }
            if (appended.length() > 0) {
                out.append("\n##### 程序写回时补充的配置项 #####\n").append(appended);
            }
            Files.write(file, out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            dirtyKeys.clear();
        } catch (Exception ignored) {
        }
    }

    /** 标量序列化：字符串加双引号（转义 \ 与 "），布尔/数字原样；非标量返回 null（不就地写）。 */
    private static String serializeScalar(Object v) {
        if (v == null) return "\"\"";
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        if (v instanceof String) {
            String s = ((String) v).replace("\\", "\\\\").replace("\"", "\\\"");
            return "\"" + s + "\"";
        }
        return null;
    }
}
