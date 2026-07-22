package org.windy.xingtubot.ext.xtgroup.reply;

import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.image.TextImageRenderer;
import org.windy.xingtubot.common.reply.PlaceholderResolver;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 自定义问答服务：加载 replies.yml，匹配群消息，替换占位符，按类型发送
 * （文本 / 本地图片 / 文字生图 / Markdown）。
 *
 * <p>图片走 base64 直传（event.replyImageData），不依赖 SCF。
 */
public class CustomReplyService {

    private final List<CustomReply> replies = new ArrayList<>();
    private final File imagesDir;          // 本地图片目录（type=image）
    private final TextImageRenderer textImage;
    private final PlaceholderResolver resolver;
    private final Consumer<String> logger;

    public CustomReplyService(File repliesFile, File imagesDir, TextImageRenderer textImage,
                              PlaceholderResolver resolver, Consumer<String> logger) {
        this.imagesDir = imagesDir;
        this.textImage = textImage;
        this.resolver = resolver;
        this.logger = logger;
        load(repliesFile);
    }

    @SuppressWarnings("unchecked")
    private void load(File file) {
        if (file == null || !file.exists()) return;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return;
            Map<String, Object> rootMap = (Map<String, Object>) root;

            // ===== 加载 replies 部分（自定义问答）=====
            Object list = rootMap.get("replies");
            if (!(list instanceof List)) return;
            for (Object item : (List<Object>) list) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                CustomReply r = new CustomReply();
                r.trigger = str(m, "trigger", "");
                r.match = parseMatch(str(m, "match", "equals"));
                // 默认 markdown：所有回复默认走 markdown 渲染（更统一的审美）。
                // 需要纯文本时显式写 type: text。
                r.type = parseType(str(m, "type", "markdown"));
                r.content = str(m, "content", "");
                r.file = str(m, "file", "");
                r.template = str(m, "template", "default");
                // 解析 buttons 列表
                Object btnsObj = m.get("buttons");
                if (btnsObj instanceof List) {
                    for (Object btnObj : (List<Object>) btnsObj) {
                        if (!(btnObj instanceof Map)) continue;
                        Map<String, Object> bm = (Map<String, Object>) btnObj;
                        CustomReply.Button b = new CustomReply.Button();
                        b.id = str(bm, "id", "");
                        b.label = str(bm, "label", "");
                        b.data = str(bm, "data", b.label);
                        b.style = intVal(bm, "style", 1);
                        b.url = str(bm, "url", "");
                        b.permission = intVal(bm, "permission", 2);
                        b.enter = bool(bm, "enter");
                        r.buttons.add(b);
                    }
                }
                if (!r.trigger.isEmpty()) {
                    warnUnknownPlaceholders(r.trigger, r.content);
                    replies.add(r);
                }
            }
            log("已加载自定义问答 " + replies.size() + " 条");
        } catch (Exception e) {
            log("加载 replies.yml 失败: " + e.getMessage());
        }
    }

    /** 已知的内置 {占位符}（PAPI 的 %xxx% 是动态的，不在此校验）。 */
    private static final java.util.Set<String> KNOWN_PLACEHOLDERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "online", "sender", "date", "time", "max", "player_names", "bot", "args"));
    private static final java.util.regex.Pattern PLACEHOLDER_PATTERN =
            java.util.regex.Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    /** 扫描内容里的 {占位符}，对未知的发警告（不阻断加载，方便排查拼写错误）。 */
    private void warnUnknownPlaceholders(String trigger, String content) {
        if (content == null || content.isEmpty()) return;
        java.util.regex.Matcher m = PLACEHOLDER_PATTERN.matcher(content);
        java.util.Set<String> unknown = null;
        while (m.find()) {
            String name = m.group(1);
            if (!KNOWN_PLACEHOLDERS.contains(name)) {
                if (unknown == null) unknown = new java.util.LinkedHashSet<>();
                unknown.add(name);
            }
        }
        if (unknown != null) {
            log("⚠️ 自定义回复「" + trigger + "」含未知占位符 " + unknown
                    + "，将原样输出。内置占位符：" + KNOWN_PLACEHOLDERS + "；PAPI 用 %xxx% 形式。");
        }
    }

    /** 纯查询：是否有匹配的 trigger（不发送消息）。 */
    public boolean canHandle(BotMessageContext event) {
        String msg = event.getMessage() == null ? "" : event.getMessage().trim();
        if (msg.isEmpty()) return false;
        for (CustomReply r : replies) {
            if (r.matches(msg)) return true;
        }
        return false;
    }

    /** 匹配并发送回复。命中返回 true。 */
    public boolean tryHandle(BotMessageContext event) {
        String msg = event.getMessage() == null ? "" : event.getMessage().trim();
        if (msg.isEmpty()) return false;

        for (CustomReply r : replies) {
            if (!r.matches(msg)) continue;
            try {
                dispatch(r, event);
            } catch (Exception e) {
                log("自定义问答发送失败(" + r.trigger + "): " + e.getMessage());
                event.reply("回复出错了~");
            }
            return true;
        }
        return false;
    }

    private void dispatch(CustomReply r, BotMessageContext event) throws Exception {
        // 图片纯走文件，不需要占位符
        if (r.type == CustomReply.Type.IMAGE) {
            File img = new File(imagesDir, r.file);
            if (!img.exists()) {
                event.reply("图片不存在：" + r.file);
                return;
            }
            byte[] bytes = Files.readAllBytes(img.toPath());
            event.replyImageData(bytes, "");
            return;
        }
        // 其余先异步解析占位符（含可能的 PAPI 跨服），拿到结果再发
        resolve(r.content, event, resolved -> {
            try {
                switch (r.type) {
                    case TEXTIMAGE:
                        event.replyImageData(textImage.render(r.template, resolved), "");
                        break;
                    case MARKDOWN:
                        event.replyMarkdown(resolved, null);
                        break;
                    case TEXT:
                    default:
                        event.reply(resolved);
                }
            } catch (Exception e) {
                log("发送失败(" + r.trigger + "): " + e.getMessage());
            }
        });
    }

    private void resolve(String text, BotMessageContext event, java.util.function.Consumer<String> cb) {
        if (text == null) { cb.accept(""); return; }
        if (resolver != null) resolver.resolve(text, event, cb);
        else cb.accept(text);
    }

    public int count() {
        return replies.size();
    }

    /** 获取所有 trigger 关键词（供 AI 模块排除）。 */
    public List<String> getAllTriggers() {
        List<String> triggers = new ArrayList<>();
        for (CustomReply r : replies) {
            if (r.trigger != null && !r.trigger.isEmpty()) {
                triggers.add(r.trigger);
            }
        }
        return triggers;
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

    private static CustomReply.Match parseMatch(String s) {
        switch (s.trim().toLowerCase()) {
            case "contains":   return CustomReply.Match.CONTAINS;
            case "startswith": return CustomReply.Match.STARTS_WITH;
            case "regex":      return CustomReply.Match.REGEX;
            default:           return CustomReply.Match.EQUALS;
        }
    }

    private static CustomReply.Type parseType(String s) {
        switch (s.trim().toLowerCase()) {
            case "image":     return CustomReply.Type.IMAGE;
            case "textimage": return CustomReply.Type.TEXTIMAGE;
            case "markdown":  return CustomReply.Type.MARKDOWN;
            default:          return CustomReply.Type.TEXT;
        }
    }

    /** 从按钮列表构建 QQ keyboard JSON。 */
    private String buildKeyboard(List<CustomReply.Button> buttons) {
        JsonObject keyboard = new JsonObject();
        JsonObject content = new JsonObject();
        JsonArray rows = new JsonArray();

        JsonArray row = new JsonArray();
        for (int i = 0; i < buttons.size(); i++) {
            CustomReply.Button b = buttons.get(i);
            JsonObject btn = new JsonObject();
            btn.addProperty("id", b.id.isEmpty() ? String.valueOf(i + 1) : b.id);

            JsonObject renderData = new JsonObject();
            renderData.addProperty("label", b.label);
            renderData.addProperty("visited_label", b.label);
            renderData.addProperty("style", b.style);
            btn.add("render_data", renderData);

            JsonObject action = new JsonObject();
            if (!b.url.isEmpty()) {
                action.addProperty("type", 1); // 跳转 URL
                action.addProperty("url", b.url);
            } else {
                action.addProperty("type", 2); // 发送消息
                action.addProperty("data", b.data.isEmpty() ? b.label : b.data);
                action.addProperty("enter", b.enter);
            }
            JsonObject permission = new JsonObject();
            permission.addProperty("type", b.permission);
            action.add("permission", permission);
            action.addProperty("unsupport_tips", "当前版本不支持按钮消息");
            btn.add("action", action);

            row.add(btn);
        }
        JsonObject rowObj = new JsonObject();   // QQ 键盘每行须是 {"buttons":[...]} 对象
        rowObj.add("buttons", row);
        rows.add(rowObj);
        content.add("rows", rows);
        keyboard.add("content", content);
        return keyboard.toString(); // 返回 JSON 字符串，gson 不跨插件边界
    }

    private void log(String m) {
        if (logger != null) logger.accept("[自定义问答] " + m);
    }

    private static int intVal(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (Exception e) { return def; }
        }
        return def;
    }
}
