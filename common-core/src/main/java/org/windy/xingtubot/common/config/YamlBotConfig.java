package org.windy.xingtubot.common.config;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台无关的 YAML 配置实现：直接读 {@code <dataFolder>/config.yml}。
 *
 * <p>由 {@link VelocityConfig} 泛化而来——它本就只依赖文件 + SnakeYAML，不碰任何平台 API，
 * 故上提到 common-core 供<b>附属扩展插件</b>在 Bukkit / Velocity 双平台共用同一份实现，
 * 避免每个扩展各自复制一份配置读取逻辑。
 *
 * <p>首次启动从 {@code resourceLoader} 指向的 jar 内默认 {@code config.yml} 释放到数据目录，
 * 之后由 {@link ConfigMerger} 把模板与用户文件合并：补全缺失键（含嵌套）、注释废弃键、保留注释，
 * 仅在结构有差异时才重写（改前备份 {@code .bak}）。
 */
public class YamlBotConfig implements BotConfig {

    private final File dataDir;
    private final ClassLoader resourceLoader;
    private final String resourceName;
    private final Map<String, Object> data;
    // 经 set() 改动过、待写回的顶层键。save() 只就地替换这些键所在的行，其余行原样保留。
    private final Set<String> dirtyKeys = new HashSet<>();

    public YamlBotConfig(File dataDir, ClassLoader resourceLoader) {
        this(dataDir, resourceLoader, "config.yml");
    }

    @SuppressWarnings("unchecked")
    public YamlBotConfig(File dataDir, ClassLoader resourceLoader, String resourceName) {
        this.dataDir = dataDir;
        this.resourceLoader = resourceLoader != null ? resourceLoader : YamlBotConfig.class.getClassLoader();
        this.resourceName = resourceName;
        Map<String, Object> loaded = new HashMap<>();
        try {
            Files.createDirectories(dataDir.toPath());
            Path file = dataDir.toPath().resolve(resourceName);
            if (!Files.exists(file)) {
                try (InputStream in = this.resourceLoader.getResourceAsStream(resourceName)) {
                    if (in != null) Files.copy(in, file);
                }
            }
            // 合并模板：补缺失键(含嵌套)+注释废弃键+保留注释；仅结构有差异时才重写
            ConfigMerger.sync(file, this.resourceLoader, resourceName);
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
     * 文件里找不到的标量脏键追加到末尾；非标量脏键不就地处理（本类的 set 仅用于写凭据等标量）。
     */
    public void save() {
        if (dirtyKeys.isEmpty()) return;
        try {
            Path file = dataDir.toPath().resolve(resourceName);
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
                if (scalar == null) { remaining.remove(key); continue; } // 非标量：原文不动
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
