package org.windy.xingtubot.common.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 交互式 MCMOD 搜索服务 + 自动 Session 清理（平台无关）。
 */
public class McmodApiService {

    private static final String SEARCH_URL = "https://mcmod-api.zkitefly.eu.org/s/";
    private static final String DETAIL_URL = "https://mcmod-api.zkitefly.eu.org/d/";
    private static final long SESSION_TTL = 60_000;        // 单个会话有效期 60 秒
    private static final long CLEAN_INTERVAL = 300_000;    // 清理间隔 5 分钟

    private static Proxy proxy = Proxy.NO_PROXY;
    private final Gson gson = new GsonBuilder().create();
    private final BotLogger logger;

    /** formId -> 搜索会话 */
    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "McmodSessionCleaner");
        t.setDaemon(true);
        return t;
    });

    public McmodApiService(BotLogger logger) {
        this.logger = logger;
        cleaner.scheduleAtFixedRate(this::cleanExpiredSessions, 0L, CLEAN_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void warn(String msg) {
        if (logger != null) logger.warn(msg);
    }

    private static class SearchSession {
        final String keyword;
        final List<Entry> results;
        final long createTime = System.currentTimeMillis();

        SearchSession(String keyword, List<Entry> results) {
            this.keyword = keyword;
            this.results = results;
        }
    }

    // ==================== 公共接口 ====================

    public static void setProxy(String host, int port, String type) {
        if (host == null || host.isEmpty() || type == null) {
            proxy = Proxy.NO_PROXY;
            return;
        }

        SocketAddress addr = new InetSocketAddress(host, port);
        switch (type.toLowerCase()) {
            case "socks":
                proxy = new Proxy(Proxy.Type.SOCKS, addr);
                break;
            case "http":
                proxy = new Proxy(Proxy.Type.HTTP, addr);
                break;
            default:
                proxy = Proxy.NO_PROXY;
                break;
        }
    }

    /** 主消息入口：处理 "/mod 关键词" 搜索与序号选择 */
    public void handleMessage(BotMessageEvent event) {
        String formId = event.getFormId();
        String msg = event.getMessage().trim();

        // Step ① 搜索
        if (msg.toLowerCase().startsWith("/mod ")) {
            String keyword = msg.substring(5).trim();
            List<Entry> results = search(keyword);
            if (results.isEmpty()) {
                event.reply("⚠️ 没找到与 “" + keyword + "” 相关的条目。");
                return;
            }

            sessions.put(formId, new SearchSession(keyword, results));

            StringBuilder sb = new StringBuilder();
            sb.append("※找到以下与 “").append(keyword).append("” 相关的条目：\n");
            for (int i = 0; i < results.size(); i++) {
                Entry e = results.get(i);
                sb.append(String.format("%d️. %s (%s)%n", i + 1, e.title,
                        e.cname.isEmpty() ? e.title : e.cname));
            }
            sb.append("\n※ 请回复序号(@阿莹 <序号>)查看详情（60秒内有效）");
            event.reply(sb.toString());
            return;
        }

        // Step ② 选择序号
        if (msg.matches("\\d+")) {
            SearchSession session = sessions.get(formId);
            if (session == null || System.currentTimeMillis() - session.createTime > SESSION_TTL) {
                sessions.remove(formId);
                event.reply("⚠️ 没有可用的搜索上下文，请重新输入 “/mod 模组名”。");
                return;
            }

            int index = Integer.parseInt(msg) - 1;
            if (index < 0 || index >= session.results.size()) {
                event.reply("❌ 无效序号，请输入 1-" + session.results.size() + "。");
                return;
            }

            Entry target = session.results.get(index);
            sessions.remove(formId);
            event.reply("正在查询「" + target.title + "」详情，请稍候...");
            if (markdownEnabled) {
                // 卡片化：Markdown 渲染（链接可点、字段分行）；不支持的通道自动降级为文本
                event.replyMarkdown(getDetailMarkdown(target), null);
            } else {
                event.reply(getDetail(target));
            }
        }
    }

    /** 是否用 Markdown 卡片输出详情（需机器人有原生 markdown 权限）。 */
    private volatile boolean markdownEnabled = false;

    public void setMarkdownEnabled(boolean enabled) {
        this.markdownEnabled = enabled;
    }

    // ==================== 搜索与详情 ====================

    private List<Entry> search(String keyword) {
        List<Entry> list = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, "UTF-8");
            String url = SEARCH_URL + "key=" + encoded + "&site=&filter=1&mold=1";
            String json = fetchJson(url);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return list;
            }

            for (JsonElement e : root.getAsJsonArray()) {
                JsonObject obj = e.getAsJsonObject();
                JsonObject data = obj.getAsJsonObject("data");
                int id = opt(data, "mcmod_id", -1);
                String title = opt(obj, "title");
                String cname = opt(data, "chinese_name");
                String desc = cleanText(opt(obj, "description"));
                String address = opt(obj, "address");

                JsonArray authorsArr = data.getAsJsonArray("authors");
                String authors = authorsArr != null && authorsArr.size() > 0
                        ? authorsArr.get(0).getAsJsonObject().get("name").getAsString()
                        : "未知作者";

                JsonArray tagsArr = data.getAsJsonArray("tag_links");
                String tags = tagsArr != null && tagsArr.size() > 0
                        ? tagsArr.get(0).getAsJsonObject().get("text").getAsString()
                        : "";

                list.add(new Entry(id, title, cname, "", desc, authors, tags, address));
            }
        } catch (Exception ex) {
            warn("[MCMOD] 搜索失败: keyword='" + keyword + "', error=" + ex.getMessage());
        }
        return list;
    }

    private static class Entry {
        final int id;
        final String title;
        final String cname;
        final String mold;
        final String description;
        final String authors;
        final String tags;
        final String address;

        Entry(int id, String title, String cname, String mold, String desc, String authors, String tags, String address) {
            this.id = id;
            this.title = title;
            this.cname = cname;
            this.mold = mold;
            this.description = desc;
            this.authors = authors;
            this.tags = tags;
            this.address = address;
        }
    }

    private int opt(JsonObject obj, String key, int defaultValue) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
                ? obj.get(key).getAsInt()
                : defaultValue;
    }

    private String getDetail(Entry e) {
        if (e.address == null || e.address.isEmpty()) {
            return formatSimpleResult(e);
        }
        try {
            String path = e.address.replace("https://www.mcmod.cn/", "").replace(".html", "");
            String[] parts = path.split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid address format: " + e.address);
            }
            String type = parts[0];
            String idFromUrl = parts[1];

            // 只有 class / modpack 才调用详情 API，其它类型直接返回概览
            if (!Arrays.asList("class", "modpack").contains(type)) {
                return formatSimpleResult(e);
            }

            String detailUrl = DETAIL_URL + type + "/" + idFromUrl;
            String json = fetchJson(detailUrl);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String fullDescription = cleanText(opt(root, "description"));
            if (fullDescription.isEmpty()) {
                fullDescription = e.description;
            }
            return parseModDetail(json, e.title, e.cname, fullDescription);
        } catch (Exception ex) {
            warn("[MCMOD] 获取详情失败: title='" + e.title + "', error=" + ex.getMessage());
            return formatSimpleResult(e);
        }
    }

    // ==================== HTTP 请求 ====================

    private String fetchJson(String urlPath) throws IOException {
        IOException lastExc = null;
        for (int i = 1; i <= 3; i++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlPath);
                conn = (HttpURLConnection) url.openConnection(proxy);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "风吟的机器人");
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("HTTP " + responseCode);
                }

                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    return sb.toString();
                }
            } catch (IOException e) {
                lastExc = e;
                warn("[MCMOD] 第 " + i + " 次请求失败：" + e.getMessage());
                try {
                    Thread.sleep(800L * i);
                } catch (InterruptedException ignored) {
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw lastExc;
    }

    // ==================== JSON 解析与工具 ====================

    private String parseModDetail(String json, String title, String cname, String fullDescription) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            String subtitle = opt(root, "subtitle");
            String author = parseAuthors(root.getAsJsonArray("authors"));
            String mcVersion = parseVersions(root.getAsJsonObject("supported_versions"));
            String env = opt(root, "operating_environment");
            String tags = parseTags(root.getAsJsonArray("tag_links"));
            String lastEdit = opt(root, "last_edit_time");
            String shortName = opt(root, "short_name");

            return String.format(
                    "※ %s\n" +
                            "【简称】 %s\n" +
                            "【作者】 %s\n" +
                            "【标签】 %s\n" +
                            "【支持版本】 %s\n" +
                            "【运行环境】 %s\n" +
                            "【最后更新】 %s\n" +
                            "【简介】 %s",
                    subtitle, shortName, author, tags, mcVersion, env, lastEdit, fullDescription
            );
        } catch (Exception e) {
            warn("[MCMOD] 解析详情失败: " + e.getMessage());
            return "⚠️ 解析失败: " + e.getMessage();
        }
    }

    private String parseAuthors(JsonArray authorsArr) {
        if (authorsArr == null || authorsArr.size() == 0) return "未知作者";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : authorsArr) {
            JsonObject authorObj = e.getAsJsonObject();
            String name = opt(authorObj, "name");
            String position = opt(authorObj, "position");
            sb.append(name).append(" (").append(position).append("), ");
        }
        return sb.toString().endsWith(", ")
                ? sb.substring(0, sb.length() - 2)
                : sb.toString();
    }

    private String parseVersions(JsonObject versions) {
        if (versions == null) return "未知";
        StringBuilder sb = new StringBuilder();
        for (String loader : versions.keySet()) {
            JsonArray versionList = versions.getAsJsonArray(loader);
            List<String> vers = new ArrayList<>();
            for (JsonElement ve : versionList) vers.add(ve.getAsString());
            sb.append(loader).append(": ").append(String.join(", ", vers)).append("\n");
        }
        return sb.toString().trim();
    }

    private String parseTags(JsonArray tagsArr) {
        if (tagsArr == null) return "无";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : tagsArr) {
            sb.append(opt(e.getAsJsonObject(), "text")).append(" ");
        }
        return sb.toString().trim();
    }

    private String opt(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
                ? obj.get(key).getAsString() : "";
    }

    private String cleanText(String src) {
        if (src == null) return "";
        return src.replaceAll("\\[.*?]", "")
                .replaceAll("https?://\\S+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String formatSimpleResult(Entry e) {
        return "📋 " + e.title + "（" + (e.cname.isEmpty() ? e.title : e.cname) + "）\n" +
                "作者: " + e.authors + "\n" +
                "标签: " + e.tags + "\n" +
                "链接: " + e.address + "\n" +
                "简介: " + (e.description.length() > 1000 ? e.description.substring(0, 1000) + "…" : e.description);
    }

    // ==================== Markdown 卡片化 ====================

    /** 详情的 Markdown 版本（链接可点、字段分行）。失败回退到简洁文本。 */
    private String getDetailMarkdown(Entry e) {
        try {
            if (e.address != null && !e.address.isEmpty()) {
                String path = e.address.replace("https://www.mcmod.cn/", "").replace(".html", "");
                String[] parts = path.split("/");
                if (parts.length >= 2 && Arrays.asList("class", "modpack").contains(parts[0])) {
                    String json = fetchJson(DETAIL_URL + parts[0] + "/" + parts[1]);
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    String subtitle = opt(root, "subtitle");
                    String author = parseAuthors(root.getAsJsonArray("authors"));
                    String mcVersion = parseVersions(root.getAsJsonObject("supported_versions"))
                            .replace("\n", "；");
                    String env = opt(root, "operating_environment");
                    String tags = parseTags(root.getAsJsonArray("tag_links"));
                    String lastEdit = opt(root, "last_edit_time");
                    String desc = cleanText(opt(root, "description"));
                    if (desc.isEmpty()) desc = e.description;
                    if (desc.length() > 400) desc = desc.substring(0, 400) + "…";
                    return mdCard(subtitle.isEmpty() ? e.title : subtitle,
                            author, tags, mcVersion, env, lastEdit, desc, e.address);
                }
            }
        } catch (Exception ex) {
            warn("[MCMOD] Markdown 详情失败: " + ex.getMessage());
        }
        // 回退：概览卡片
        String desc = e.description.length() > 400 ? e.description.substring(0, 400) + "…" : e.description;
        return mdCard(e.cname.isEmpty() ? e.title : e.cname + "（" + e.title + "）",
                e.authors, e.tags, "—", "—", "—", desc, e.address);
    }

    private String mdCard(String title, String author, String tags, String version,
                          String env, String lastEdit, String desc, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n");
        if (author != null && !author.isEmpty()) sb.append("**作者** ").append(author).append("\n");
        if (tags != null && !tags.isEmpty()) sb.append("**标签** ").append(tags).append("\n");
        if (version != null && !version.isEmpty()) sb.append("**支持版本** ").append(version).append("\n");
        if (env != null && !env.isEmpty() && !env.equals("—")) sb.append("**环境** ").append(env).append("\n");
        if (lastEdit != null && !lastEdit.isEmpty() && !lastEdit.equals("—"))
            sb.append("**更新** ").append(lastEdit).append("\n");
        sb.append("\n> ").append(desc).append("\n");
        if (url != null && !url.isEmpty()) sb.append("\n[🔗 在 MC百科 查看](").append(url).append(")");
        return sb.toString();
    }

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(en -> now - en.getValue().createTime > SESSION_TTL);
    }
}
