package org.windy.xingtubot.core.api;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 交互式 MCMOD 搜索服务 + 自动 Session 清理
 * (兼容 Java 8)
 */
public class McmodApiService {

    private static final String SEARCH_URL = "https://mcmod-api.zkitefly.eu.org/s/";
    private static final String DETAIL_URL = "https://mcmod-api.zkitefly.eu.org/d/";
    private static final long SESSION_TTL = 60_000;        // 单个会话有效期 60 秒
    private static final long CLEAN_INTERVAL = 300_000;    // 清理间隔 5 分钟

    private static Proxy proxy = Proxy.NO_PROXY;
    private final Gson gson = new GsonBuilder().create();

    /** formId -> 搜索会话 */
    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();

    /** 守护线程定时清理会话 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "McmodSessionCleaner");
        t.setDaemon(true);
        return t;
    });

    public McmodApiService() {
        // 修复 scheduleAtFixedRate 参数：Runnable, initialDelay(0=立即开始), period, unit
        cleaner.scheduleAtFixedRate(this::cleanExpiredSessions, 0L, CLEAN_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /** 会话结构 */
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
        if (host == null || host.isEmpty()) {
            proxy = Proxy.NO_PROXY;
            return;
        }

        SocketAddress addr = new InetSocketAddress(host, port);
        if (type == null) {
            proxy = Proxy.NO_PROXY;
            return;
        }

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

    /** 主消息入口 */
    public void handleMessage(BotMessageEvent event) {
        String formId = event.getFormId();
        String msg = event.getMessage().trim();

        System.out.println(">> [McmodApiService] handleMessage: formId=" + formId + ", msg='" + msg + "'"); // 新增日志

        // Step ① -- 搜索
        // 🔧 修复：更改变更为 /mod (ignoreCase)
        if (msg.toLowerCase().startsWith("/mod ")) {
            String keyword = msg.substring(5).trim();  // 移除 "/mod " 前缀，直接 substring
            System.out.println(">> [McmodApiService] 触发搜索: keyword='" + keyword + "'"); // 新增日志
            List<Entry> results = search(keyword);
            if (results.isEmpty()) {
                System.out.println(">> [McmodApiService] 搜索无结果: keyword='" + keyword + "'"); // 新增日志
                event.reply("⚠️ 没找到与 “" + keyword + "” 相关的条目。");
                return;
            }

            sessions.put(formId, new SearchSession(keyword, results));
            System.out.println(">> [McmodApiService] 会话创建: formId=" + formId + ", 结果数量=" + results.size()); // 新增日志

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

        // Step ② -- 选择序号 (无变化)
        if (msg.matches("\\d+")) {
            System.out.println(">> [McmodApiService] 触发序号选择: msg='" + msg + "', formId=" + formId); // 新增日志
            SearchSession session = sessions.get(formId);
            if (session == null || System.currentTimeMillis() - session.createTime > SESSION_TTL) {
                sessions.remove(formId);
                System.out.println(">> [McmodApiService] 会话过期或不存在: formId=" + formId); // 新增日志
                event.reply("⚠️ 没有可用的搜索上下文，请重新输入 “/mod 模组名”。");
                return;
            }

            int index = Integer.parseInt(msg) - 1;
            if (index < 0 || index >= session.results.size()) {
                System.out.println(">> [McmodApiService] 无效序号: index=" + index + ", 最大=" + (session.results.size() - 1)); // 新增日志
                event.reply("❌ 无效序号，请输入 1-" + session.results.size() + "。");
                return;
            }

            Entry target = session.results.get(index);
            sessions.remove(formId);
            System.out.println(">> [McmodApiService] 选中条目: title='" + target.title + "', address='" + target.address + "'"); // 新增日志
            event.reply("正在查询「" + target.title + "」详情，请稍候...");

            event.reply(getDetail(target));
        }
    }

    // ==================== 搜索与详情 ====================

    private List<Entry> search(String keyword) {
        List<Entry> list = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, "UTF-8");
            String url = SEARCH_URL + "key=" + encoded + "&site=&filter=1&mold=1";
            System.out.println(">> [McmodApiService] 搜索请求 URL: " + url); // 新增日志
            String json = fetchJson(url);
            System.out.println(">> [McmodApiService] 搜索响应 JSON 开始 (前500字符): " + json.substring(0, Math.min(500, json.length()))); // 新增日志
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                System.out.println(">> [McmodApiService] 搜索响应不是数组: root=" + root); // 新增日志
                return list;
            }

            for (JsonElement e : root.getAsJsonArray()) {
                JsonObject obj = e.getAsJsonObject();
                // 解析嵌套字段
                JsonObject data = obj.getAsJsonObject("data");
                int id = opt(data, "mcmod_id", -1);
                String title = opt(obj, "title");
                String cname = opt(data, "chinese_name");
                String desc = cleanText(opt(obj, "description"));
                String address = opt(obj, "address"); // 修改：获取 address
                System.out.println(">> [McmodApiService] 解析条目: id=" + id + ", title='" + title + "', address='" + address + "'"); // 新增日志

                // 新增字段解析
                JsonArray authorsArr = data.getAsJsonArray("authors");
                String authors = authorsArr != null && authorsArr.size() > 0
                        ? authorsArr.get(0).getAsJsonObject().get("name").getAsString()
                        : "未知作者";

                JsonArray tagsArr = data.getAsJsonArray("tag_links");
                String tags = tagsArr != null && tagsArr.size() > 0
                        ? tagsArr.get(0).getAsJsonObject().get("text").getAsString()
                        : "";

                list.add(new Entry(id, title, cname, "", desc, authors, tags, address)); // 新增authors/tags/ address字段
            }
        } catch (Exception ex) {
            System.err.println(">> [McmodApiService] 搜索失败: keyword='" + keyword + "', error=" + ex.getMessage()); // 新增日志
            ex.printStackTrace();
        }
        return list;
    }

    // 修改Entry类结构
    private static class Entry {
        final int id;
        final String title;
        final String cname;
        final String mold;
        final String description;
        final String authors; // 新增
        final String tags;    // 新增
        final String address; // 新增
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

    // 新增安全获取字段方法（带默认值）
    private int opt(JsonObject obj, String key, int defaultValue) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
                ? obj.get(key).getAsInt()
                : defaultValue;
    }

    private String getDetail(Entry e) {
        if (e.address == null || e.address.isEmpty()) {
            System.out.println(">> [McmodApiService] address 为空，跳过详情查询: title='" + e.title + "'"); // 新增日志
            return formatSimpleResult(e);
        }
        try {
            // 解析 address，例如：https://www.mcmod.cn/class/385.html -> type=class, id=385
            String path = e.address.replace("https://www.mcmod.cn/", "").replace(".html", "");
            String[] parts = path.split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid address format: " + e.address);
            }
            String type = parts[0]; // e.g., class
            String idFromUrl = parts[1]; // e.g., 385
            System.out.println(">> [McmodApiService] 解析 address: type='" + type + "', idFromUrl='" + idFromUrl + "', full address='" + e.address + "'"); // 新增日志

            // 🔧 核心改进：检查 type，只有 class 或 modpack 才调用详情 API；其他（如 post/mod）直接返回概览（见文档）
            // 兼容 Java 8：使用 Arrays.asList 而非 Set.of
            if (!Arrays.asList("class", "modpack").contains(type)) {
                System.out.println(">> [McmodApiService] 类型 '" + type + "' 不支持详情 API，直接返回概览: title='" + e.title + "'"); // 新增日志
                return formatSimpleResult(e); // 转为概览模式
            }

            String detailUrl = DETAIL_URL + type + "/" + idFromUrl; // e.g., https://mcmod-api.zkitefly.eu.org/d/class/385
            System.out.println(">> [McmodApiService] 详情请求 URL: " + detailUrl); // 新增日志
            String json = fetchJson(detailUrl);
            System.out.println(">> [McmodApiService] 详情响应 JSON 开始 (前500字符): " + json.substring(0, Math.min(500, json.length()))); // 新增日志
            // 🔧 修复：获取详情 API 的完整 description，如果为空则回退到搜索的 e.description（避免空白）
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String fullDescription = cleanText(opt(root, "description"));
            if (fullDescription.isEmpty()) {
                fullDescription = e.description;  // 回退到搜索的描述
            }
            return parseModDetail(json, e.title, e.cname, fullDescription);
        } catch (Exception ex) {
            System.err.println(">> [McmodApiService] 获取详情失败: title='" + e.title + "', address='" + e.address + "', error=" + ex.getMessage()); // 新增日志
            ex.printStackTrace();
            // 🔧 如果详情失败，也返回概览，避免用户什么都看不到
            return formatSimpleResult(e); // 降级到概览
        }
    }

    // ==================== HTTP 请求 ====================

    private String fetchJson(String urlPath) throws IOException {
        IOException lastExc = null;
        for (int i = 1; i <= 3; i++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlPath);
                System.out.println(">> [McmodApiService] HTTP 请求尝试 #" + i + ": URL=" + urlPath + ", proxy=" + proxy); // 新增详细日志
                conn = (HttpURLConnection) url.openConnection(proxy);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "风吟的机器人");
                int responseCode = conn.getResponseCode(); // 修改：提前获取状态码
                System.out.println(">> [McmodApiService] HTTP 响应状态码: " + responseCode); // 新增日志
                if (responseCode != 200)
                    throw new IOException("HTTP " + responseCode);

                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    System.out.println(">> [McmodApiService] HTTP 响应大小: " + sb.length() + " 字符"); // 新增日志
                    return sb.toString();
                }
            } catch (IOException e) {
                lastExc = e;
                System.err.println(">> [McmodApiService] ⚠️ 第 " + i + " 次请求失败：" + e.getMessage()); // 更新日志前缀
                try { Thread.sleep(800L * i); } catch (InterruptedException ignored) {}
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
            // 🔧 基于实际返回 JSON，直接使用 root（无 nested "data"）

            // 基础信息（基于文档和实际 JSON）
            String subtitle = opt(root, "subtitle");
            String author = parseAuthors(root.getAsJsonArray("authors"));
            String mcVersion = parseVersions(root.getAsJsonObject("supported_versions"));
            String env = opt(root, "operating_environment");
            String tags = parseTags(root.getAsJsonArray("tag_links"));
            String lastEdit = opt(root, "last_edit_time");
            String relatedLinks = parseLinks(root.getAsJsonArray("related_links")); // 🔧 保留解析但不输出
            String shortName = opt(root, "short_name");

            System.out.println(">> [McmodApiService] 解析详情成功: title='" + title + "', authors='" + author + "', versions='" + mcVersion + "'"); // 新增日志
            return String.format(
                    "※ %s\n" +
                            "【简称】 %s\n" +
                            "【作者】 %s\n" +
                            "【标签】 %s\n" +
                            "【支持版本】 %s\n" +
                            "【运行环境】 %s\n" +
                            "【最后更新】 %s\n" +
                            "【简介】 %s",
                    subtitle, shortName, author, tags, mcVersion, env, lastEdit, fullDescription  // 🔧 修复：使用详情 API 的完整描述，如果为空就用搜索的
            );
        } catch (Exception e) {
            System.err.println(">> [McmodApiService] 解析失败: " + e.getMessage()); // 新增日志
            e.printStackTrace();
            return "⚠️ 解析失败: " + e.getMessage();
        }
    }

    // 🔧 新增解析相关链接的方法（基于实际 JSON 的 related_links 数组）- 保留但不输出
    private String parseLinks(JsonArray linksArr) {
        if (linksArr == null || linksArr.size() == 0) return "无";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : linksArr) {
            JsonObject linkObj = e.getAsJsonObject();
            String text = opt(linkObj, "text");
            String url = opt(linkObj, "url");
            if (!url.isEmpty()) {
                sb.append("[").append(text).append("](").append(url).append(")  ");
            }
        }
        return sb.toString().trim();
    }

    // 新增辅助解析方法
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
                ? sb.substring(0, sb.length() - 2)  // 移除末尾 ", "
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
                "链接: " + e.address + "\n" +  // 新增链接
                "简介: " + (e.description.length() > 1000 ? e.description.substring(0, 1000) + "…" : e.description);
    }

    // ==================== Session 清理 ====================

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.entrySet().removeIf(en -> now - en.getValue().createTime > SESSION_TTL);
        int after = sessions.size();
        if (before != after)
            System.out.println("🧹 已清理过期 MCMOD 会话: " + (before - after) + " 个");
    }
}