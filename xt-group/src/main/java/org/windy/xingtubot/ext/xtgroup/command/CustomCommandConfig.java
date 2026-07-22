package org.windy.xingtubot.ext.xtgroup.command;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 自定义命令配置：从 commands.yml 加载命令定义。
 *
 * <pre>
 * commands:
 *   - trigger: "回家"
 *     description: "传送到家"
 *     as: player
 *     command: "home"
 *     need-bind: true
 *     not-bound-msg: "§c请先绑定白名单"
 *     admin: false
 *
 *   - trigger: "公告"
 *     description: "全服公告"
 *     as: console
 *     admin: true
 *     command: "say {args}"
 *
 *   - trigger: "lobby公告"
 *     as: console
 *     admin: true
 *     server: lobby
 *     command: "say 大家好"
 * </pre>
 */
public class CustomCommandConfig {

    public enum ExecAs { PLAYER, CONSOLE }

    public static class Entry {
        public String trigger = "";
        public String description = "";
        public ExecAs execAs = ExecAs.CONSOLE;
        public String command = "";
        public boolean needBind = false;
        public String notBoundMsg = "§c你还没绑定白名单，请先在群里完成绑定";
        public boolean admin = false;
        public String server = "";  // Velocity 跨服目标（空=当前服）
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Consumer<String> logger;

    public CustomCommandConfig(Consumer<String> logger) {
        this.logger = logger;
    }

    @SuppressWarnings("unchecked")
    public void load(File file) {
        if (file == null || !file.exists()) return;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return;
            Object list = ((Map<String, Object>) root).get("commands");
            if (!(list instanceof List)) return;

            for (Object item : (List<Object>) list) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                Entry e = new Entry();
                e.trigger = str(m, "trigger", "");
                e.description = str(m, "description", "");
                e.execAs = parseExecAs(str(m, "as", "console"));
                e.command = str(m, "command", "");
                e.needBind = bool(m, "need-bind");
                e.notBoundMsg = str(m, "not-bound-msg", "§c你还没绑定白名单，请先在群里完成绑定");
                e.admin = bool(m, "admin");
                e.server = str(m, "server", "");
                if (!e.trigger.isEmpty() && !e.command.isEmpty()) {
                    warnUnknownPlaceholders(e.trigger, e.command);
                    entries.add(e);
                }
            }
            log("已加载自定义命令 " + entries.size() + " 条");
        } catch (Exception e) {
            log("加载 commands.yml 失败: " + e.getMessage());
        }
    }

    /** 已知的内置 {占位符}（命令额外支持 {args}；%player_name% 等 PAPI 风格不在此校验）。 */
    private static final java.util.Set<String> KNOWN_PLACEHOLDERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "online", "sender", "date", "time", "max", "player_names", "bot", "menu", "args"));
    private static final java.util.regex.Pattern PLACEHOLDER_PATTERN =
            java.util.regex.Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    /** 扫描命令模板里的 {占位符}，对未知的发警告（不阻断加载，方便排查拼写错误）。 */
    private void warnUnknownPlaceholders(String trigger, String command) {
        if (command == null || command.isEmpty()) return;
        java.util.regex.Matcher m = PLACEHOLDER_PATTERN.matcher(command);
        java.util.Set<String> unknown = null;
        while (m.find()) {
            String name = m.group(1);
            if (!KNOWN_PLACEHOLDERS.contains(name)) {
                if (unknown == null) unknown = new java.util.LinkedHashSet<>();
                unknown.add(name);
            }
        }
        if (unknown != null) {
            log("⚠️ 自定义命令「" + trigger + "」含未知占位符 " + unknown
                    + "，将原样传入命令。内置占位符：" + KNOWN_PLACEHOLDERS + "；玩家名用 %player_name%。");
        }
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * 匹配一条消息。返回匹配的 Entry，或 null。
     * 消息格式：trigger 或 trigger args
     */
    public Entry match(String message) {
        String trimmed = message.trim();
        for (Entry e : entries) {
            if (trimmed.equals(e.trigger) || trimmed.startsWith(e.trigger + " ")) {
                return e;
            }
        }
        return null;
    }

    /**
     * 从消息中提取参数（trigger 后面的部分）。
     * "回家" → ""，"tp Steve" → "Steve"
     */
    public String extractArgs(String message, Entry entry) {
        String trimmed = message.trim();
        if (trimmed.equals(entry.trigger)) return "";
        return trimmed.substring(entry.trigger.length()).trim();
    }

    // ---- helpers ----

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Boolean && (Boolean) v;
    }

    private static ExecAs parseExecAs(String s) {
        return "player".equalsIgnoreCase(s.trim()) ? ExecAs.PLAYER : ExecAs.CONSOLE;
    }

    private void log(String msg) {
        if (logger != null) logger.accept("[自定义命令] " + msg);
    }
}
