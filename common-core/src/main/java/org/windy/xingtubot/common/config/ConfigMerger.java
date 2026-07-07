package org.windy.xingtubot.common.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置同步器：把 jar 内默认 {@code config.yml}（模板=结构权威）与用户已有 {@code config.yml} 合并。
 *
 * <p>规则（模板定结构，用户文件提供取值）：
 * <ul>
 *   <li><b>补全缺失键</b>——模板里有、用户没有的键（<b>任意嵌套层级</b>）按模板默认值+注释插入到模板位置；</li>
 *   <li><b>保留用户取值</b>——两边都有的叶子键沿用用户的值（含列表内容）；</li>
 *   <li><b>注释废弃键</b>——用户有、模板已删的键不直接丢弃，而是改写成注释（{@code # [废弃] ...}）留底，便于找回；</li>
 *   <li><b>保留注释</b>——重建时带上模板里每个键紧邻上方的注释/空行。</li>
 * </ul>
 *
 * <p>仅当结构确有差异（缺键或废弃键）时才重写文件；结构一致时<b>原样不动</b>，
 * 因此稳态启动不会扰动用户文件（连用户自加的注释也保住）。重写前会备份为
 * {@code <file>.bak}。任何异常都不动用户文件。
 */
public final class ConfigMerger {

    private ConfigMerger() {}

    private static final String INDENT_UNIT = "  ";

    /**
     * 同步 userFile 与 jar 内 resourceName 模板。
     *
     * @return 是否改写了文件（true 表示调用方应重新加载内存数据）
     */
    @SuppressWarnings("unchecked")
    public static boolean sync(Path userFile, ClassLoader loader, String resourceName) {
        if (loader == null) loader = ConfigMerger.class.getClassLoader();
        try {
            List<String> templateLines = readResourceLines(loader, resourceName);
            if (templateLines == null) return false; // 没有模板，无从合并

            Object tObj = new Yaml().load(String.join("\n", templateLines));
            if (!(tObj instanceof Map)) return false;
            Map<String, Object> template = (Map<String, Object>) tObj;

            Map<String, Object> user = new LinkedHashMap<>();
            if (Files.exists(userFile)) {
                try (InputStream in = Files.newInputStream(userFile)) {
                    Object uObj = new Yaml().load(in);
                    if (uObj instanceof Map) user = (Map<String, Object>) uObj;
                }
            }

            if (!structureDiffers(template, user)) return false;

            Map<String, List<String>> comments = collectComments(templateLines);
            StringBuilder sb = new StringBuilder();
            emit(sb, template, user, "", 0, comments);

            if (Files.exists(userFile)) {
                Files.copy(userFile, userFile.resolveSibling(userFile.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(userFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<String> readResourceLines(ClassLoader loader, String resourceName) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resourceName)) {
            if (in == null) return null;
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) lines.add(l);
            }
            return lines;
        }
    }

    /** 结构差异：模板有用户无（递归到嵌套 map）或用户有模板无。值不同不算。 */
    @SuppressWarnings("unchecked")
    private static boolean structureDiffers(Map<String, Object> template, Map<String, Object> user) {
        for (Map.Entry<String, Object> e : template.entrySet()) {
            if (!user.containsKey(e.getKey())) return true;
            if (e.getValue() instanceof Map && user.get(e.getKey()) instanceof Map
                    && structureDiffers((Map<String, Object>) e.getValue(),
                                        (Map<String, Object>) user.get(e.getKey()))) {
                return true;
            }
        }
        for (String k : user.keySet()) {
            if (!template.containsKey(k)) return true;
        }
        return false;
    }

    /** 扫描模板行，建立 “完整点分路径 -> 紧邻上方注释/空行” 的映射。 */
    private static Map<String, List<String>> collectComments(List<String> lines) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        List<Integer> indents = new ArrayList<>(); // 键路径栈：缩进
        List<String> keys = new ArrayList<>();      // 键路径栈：键名
        for (String raw : lines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                pending.add(raw);
                continue;
            }
            if (trimmed.startsWith("- ")) { // 列表项不建路径（列表内不下钻）
                pending.clear();
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) { pending.clear(); continue; }
            int indent = indentOf(raw);
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                keys.remove(keys.size() - 1);
            }
            indents.add(indent);
            keys.add(trimmed.substring(0, colon).trim());
            if (!pending.isEmpty()) {
                map.put(String.join(".", keys), new ArrayList<>(pending));
                pending.clear();
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static void emit(StringBuilder sb, Map<String, Object> template, Map<String, Object> user,
                             String pathPrefix, int depth, Map<String, List<String>> comments) {
        String indent = repeat(INDENT_UNIT, depth);
        for (Map.Entry<String, Object> e : template.entrySet()) {
            String key = e.getKey();
            Object tval = e.getValue();
            String path = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;

            List<String> cs = comments.get(path);
            if (cs != null) for (String c : cs) sb.append(c).append("\n");

            boolean userHas = user != null && user.containsKey(key);
            Object uval = userHas ? user.get(key) : null;

            if (tval instanceof Map) {
                // 段落：结构跟模板，递归补子键；用户的同名 map 提供子键取值
                sb.append(indent).append(key).append(":\n");
                Map<String, Object> userSub = (uval instanceof Map) ? (Map<String, Object>) uval : null;
                emit(sb, (Map<String, Object>) tval, userSub, path, depth + 1, comments);
            } else if (tval instanceof List) {
                // 列表：整体取用户的（若类型相符），否则模板默认；列表内不逐项合并
                Object listVal = (uval instanceof List) ? uval : tval;
                sb.append(indent).append(key).append(":\n");
                appendBlock(sb, listVal, depth + 1, "");
            } else {
                // 标量叶子：用户值优先（类型相符时），否则模板默认
                Object val = (userHas && !(uval instanceof Map) && !(uval instanceof List)) ? uval : tval;
                sb.append(indent).append(key).append(": ").append(scalar(val)).append("\n");
            }
        }
        // 废弃键（用户有、模板无）→ 注释留底
        if (user != null) {
            for (Map.Entry<String, Object> e : user.entrySet()) {
                if (template.containsKey(e.getKey())) continue;
                appendCommentedOut(sb, e.getKey(), e.getValue(), depth);
            }
        }
    }

    private static void appendCommentedOut(StringBuilder sb, String key, Object value, int depth) {
        String indent = repeat(INDENT_UNIT, depth);
        if (value instanceof Map || value instanceof List) {
            sb.append(indent).append("# [废弃] ").append(key).append(":\n");
            appendBlock(sb, value, depth + 1, "# ");
        } else {
            sb.append(indent).append("# [废弃] ").append(key).append(": ").append(scalar(value)).append("\n");
        }
    }

    /** 用 SnakeYAML 块状 dump 复杂值（列表/map），整体缩进后追加；linePrefix 用于废弃键的 “# ” 注释前缀。 */
    private static void appendBlock(StringBuilder sb, Object value, int depth, String linePrefix) {
        String indent = repeat(INDENT_UNIT, depth);
        DumperOptions opt = new DumperOptions();
        opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opt.setPrettyFlow(true);
        String dumped = new Yaml(opt).dump(value);
        for (String p : dumped.split("\n", -1)) {
            if (p.isEmpty()) continue;
            sb.append(indent).append(linePrefix).append(p).append("\n");
        }
    }

    /** 标量序列化：字符串加双引号（转义 \ 与 "），布尔/数字原样，null→ 空串。 */
    private static String scalar(Object v) {
        if (v == null) return "\"\"";
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        String s = String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + s + "\"";
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
