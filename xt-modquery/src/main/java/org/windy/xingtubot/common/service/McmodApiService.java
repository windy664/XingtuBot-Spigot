package org.windy.xingtubot.common.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.util.Http;
import org.windy.xingtubot.common.util.Md;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * MCMOD（mcmod.cn）通用爬取轮子：一套代码搞定 模组 / 整合包 / 物品 / 教程 的搜索、翻页、详情。
 *
 * <p><b>搜索：</b>{@code search.mcmod.cn/s?key=词&filter=N&mold=1&page=P}，filter 决定类型
 * （1 模组、2 整合包、3 物品、4 教程），结果项 {@code div.result-item} 里链接前缀对应类型
 * （/class/、/modpack/、/item/、/post/）。翻页靠 {@code &page=}，hasNext 看分页块。
 *
 * <p><b>反爬：</b>mcmod 上了图片验证码，必须带过验证的浏览器 cookie（{@code _uuid}+{@code MCMOD_SEED}）
 * + 浏览器 UA + Referer（cookie 门、非 IP 门，已实测可跨 IP）。cookie 由 config {@code mcmod-cookie} 提供。
 */
public class McmodApiService {

    private static final String SEARCH = "https://search.mcmod.cn/s?key=";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0.0.0 Safari/537.36";

    // ===== 卡片字段上限（简介不再截断,整卡超长由 sendDetail 分段发;这里只留短字段的合理上限）=====
    private static final int VER_MAX = 220;      // 支持的 MC 版本
    private static final int COMMENT_MAX = 120;  // 单条热评正文
    private static final int HOT_COMMENTS = 3;   // 热评取前 N 条

    /** 内容类型：filter 编码 + 详情链接前缀 + 中文标签。 */
    public enum Type {
        MOD(1, "/class/", "模组"),
        MODPACK(2, "/modpack/", "整合包"),
        ITEM(3, "/item/", "物品"),
        TUTORIAL(4, "/post/", "教程");

        public final int filter;
        public final String prefix;
        public final String label;

        Type(int filter, String prefix, String label) {
            this.filter = filter;
            this.prefix = prefix;
            this.label = label;
        }
    }

    private final BotLogger logger;
    private volatile String cookie = "";

    public McmodApiService(BotLogger logger) {
        this.logger = logger;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie == null ? "" : cookie.trim();
    }

    // ==================== 数据结构 ====================

    /** 一条搜索结果。 */
    public static class Entry {
        public final String url;    // 详情页 URL（绝对）
        public final String title;  // 全名，如 "机械动力 (Create)"
        public final String cname;  // 中文名
        public final String desc;   // 列表摘要
        public final Type type;

        public Entry(String url, String title, String cname, String desc, Type type) {
            this.url = url;
            this.title = title;
            this.cname = cname;
            this.desc = desc;
            this.type = type;
        }
    }

    /** 一页搜索结果 + 翻页信息。 */
    public static class Page {
        public final List<Entry> entries;
        public final int page;       // 当前页（1 基）
        public final boolean hasNext;
        public final Type type;
        public final String keyword;

        public Page(List<Entry> entries, int page, boolean hasNext, Type type, String keyword) {
            this.entries = entries;
            this.page = page;
            this.hasNext = hasNext;
            this.type = type;
            this.keyword = keyword;
        }
    }

    // ==================== 通用搜索（含翻页） ====================

    /** 按类型+页码搜索。失败/被拦返回空页。 */
    public Page search(String keyword, Type type, int page) {
        List<Entry> out = new ArrayList<>();
        boolean hasNext = false;
        if (page < 1) page = 1;
        try {
            String url = SEARCH + URLEncoder.encode(keyword, "UTF-8")
                    + "&filter=" + type.filter + "&mold=1&page=" + page;
            debug("搜索 type=" + type.label + " 关键词=\"" + keyword + "\" page=" + page + " → " + url);
            String html = fetch(url, "https://www.mcmod.cn/");
            if (html != null) {
                Document doc = Jsoup.parse(html);
                String pat = type.prefix.replace("/", "\\/") + "\\d+\\.html";
                for (Element it : doc.select("div.result-item")) {
                    Element link = it.selectFirst("div.head a[href~=" + pat + "]");
                    if (link == null) continue;
                    String full = link.text().trim();
                    Element body = it.selectFirst("div.body");
                    String desc = body != null ? body.text().trim() : "";
                    String cname = full;
                    int p = full.indexOf(" (");
                    if (p > 0) cname = full.substring(0, p).trim();
                    out.add(new Entry(normalize(link.attr("href")), full, cname, desc, type));
                }
                hasNext = hasNextPage(doc, page);
                debug("搜索命中 " + out.size() + " 条, hasNext=" + hasNext);
            }
        } catch (Exception e) {
            warn("搜索失败: " + e.getMessage());
        }
        return new Page(out, page, hasNext, type, keyword);
    }

    /** 分页块里是否存在 page > 当前页的链接。 */
    private boolean hasNextPage(Document doc, int current) {
        for (Element a : doc.select("a.page-link[href*=page=]")) {
            String href = a.attr("href");
            int idx = href.indexOf("page=");
            if (idx < 0) continue;
            String num = href.substring(idx + 5).replaceAll("[^0-9].*$", "");
            try {
                if (Integer.parseInt(num) > current) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    // ==================== 详情（按类型分派） ====================

    /** 拉取并发送详情：先发封面图（若有），再发 markdown 卡片。一次抓取，图文一起。 */
    public void sendDetail(Entry e, org.windy.xingtubot.common.event.BotMessageEvent event) {
        debug("抓详情 type=" + e.type.label + " 标题=\"" + e.title + "\" → " + e.url);
        String html = fetch(e.url, "https://search.mcmod.cn/");
        if (html == null) {
            debug("详情抓取失败（fetch 返回 null），回退失败提示");
            event.reply(failMsg(e.title));
            return;
        }
        Document doc = Jsoup.parse(html);
        String image = "";
        String card;
        switch (e.type) {
            case MOD:
            case MODPACK:
                image = imgSrc(doc, "img[src*=/class/cover/], img[src*=/modpack/cover/]");
                card = modCardMarkdown(doc, e);
                break;
            case ITEM:
                // 物品主图标：优先 128x128 大图，退回其它 item 图
                image = imgSrc(doc, "img[src*=/item/icon/128x128/], .item-info-block img, .itemname img, img[src*=/item/]");
                card = itemCardMarkdown(doc, e);
                break;
            case TUTORIAL:
            default:
                card = genericCardMarkdown(doc, e);
        }
        // 图片嵌进 markdown（QQ 原生 markdown 图片必须带像素尺寸 ![alt #Wpx #Hpx](url)，且不能单独发）。
        // mcmod 图片 URL 自带尺寸：封面 @480x300、物品图标路径 /128x128/。
        String imgMd = imageMarkdown(image, e.type == Type.ITEM ? "图标" : "封面");
        if (!imgMd.isEmpty()) card = imgMd + "\n\n" + card;

        debug("详情解析完成: 封面图=" + (image.isEmpty() ? "无" : image)
                + ", 卡片 " + card.length() + " 字符");
        // 整卡超长按换行分段发（不截断——内容多长发多长；QQ 群 markdown 单条有硬限，切多条避免整条失败）
        for (String chunk : splitCard(card, DETAIL_CHUNK)) {
            debug("发送 markdown 分片(" + chunk.length() + "字符):\n" + chunk);
            event.replyMarkdown(chunk, null);
        }
    }

    /**
     * 构造 QQ 原生 markdown 图片语法 {@code ![alt #Wpx #Hpx](url)}（尺寸强制）。
     * 从 mcmod URL 解析尺寸：{@code ...@480x300.jpg}(缩放后缀) 或路径段 {@code /128x128/}。
     * 解析不到尺寸则返回 ""（宁可不发图，也不发无尺寸的无效图片语法 → 触发 40034011）。
     */
    private static String imageMarkdown(String url, String alt) {
        if (url == null || url.isEmpty()) return "";
        int w = 0, h = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@(\\d+)x(\\d+)").matcher(url);
        if (m.find()) { w = Integer.parseInt(m.group(1)); h = Integer.parseInt(m.group(2)); }
        else {
            m = java.util.regex.Pattern.compile("/(\\d+)x(\\d+)/").matcher(url);
            if (m.find()) { w = Integer.parseInt(m.group(1)); h = Integer.parseInt(m.group(2)); }
        }
        if (w <= 0 || h <= 0) return "";
        return "![" + alt + " #" + w + "px #" + h + "px](" + url + ")";
    }

    /** 详情单条上限（字符）。QQ 群 markdown 单条有服务端硬限，取保守值。 */
    private static final int DETAIL_CHUNK = 1500;

    /** 按最大长度分段，尽量在换行处断开（避免切断字段/语法行）。 */
    private static List<String> splitCard(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        if (text.length() <= maxLen) { chunks.add(text); return chunks; }
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + maxLen, text.length());
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end - 1);
                if (nl > pos + maxLen / 2) end = nl + 1;
            }
            chunks.add(text.substring(pos, end));
            pos = end;
        }
        return chunks;
    }

    /** 取第一个匹配 img 的 src（补全协议）。 */
    private static String imgSrc(Document doc, String selector) {
        Element img = doc.selectFirst(selector);
        if (img == null) return "";
        String src = img.attr("src").trim();
        if (src.startsWith("//")) src = "https:" + src;
        else if (src.startsWith("/")) src = "https://www.mcmod.cn" + src;
        // 去掉 mcmod 的缩放后缀里的多余参数不影响，保留 @WxH 即可（返回的是有效图）
        return src;
    }

    /** 模组 / 整合包详情卡（class / modpack 页结构一致）。 */
    private String modCardMarkdown(Document doc, Entry e) {
        String cname = textOf(doc, ".class-title h3");
        String ename = textOf(doc, ".class-title h4");
        String status = textOf(doc, ".class-status");
        String source = textOf(doc, ".class-source");
        List<String> tags = new ArrayList<>();
        for (Element a : doc.select("li.tag a")) {
            String t = a.text().trim();
            if (!t.isEmpty()) tags.add(t);
        }
        String versions = "";
        Element mcver = doc.selectFirst("li.mcver");
        if (mcver != null) versions = mcver.text().replace("支持的MC版本:", "").trim();
        List<String> hot = hotComments(e);
        String env = "", workmode = "";
        for (Element li : doc.select("li.col-lg-4")) {
            String t = li.text().trim();
            if (t.startsWith("运行环境")) env = strip(t, "运行环境");
            else if (t.startsWith("运作方式")) workmode = strip(t, "运作方式");
        }
        String intro = introMarkdown(doc); // 保留原生排版(小标题→###、列表→-、段落)，非拍平
        if (cname.isEmpty() && ename.isEmpty()) cname = e.cname;
        String authors = extractAuthors(doc);       // best-effort：空则字段自动跳过
        String depends = extractDependencies(doc, e); // best-effort：空则字段自动跳过

        Md md = Md.card(e.type == Type.MODPACK ? "🎁" : "📦",
                        cname + (ename.isEmpty() ? "" : " · " + ename))
                .field("📌", "状态", join(" · ", status, source))
                .field("🏷", "标签", String.join(" / ", tags))
                .field("🎮", "支持", trim(versions, VER_MAX))
                .field("🖥", "环境", env)
                .field("⚙", "运作", workmode)
                .field("👤", "作者", trim(authors, 120))
                .field("🔗", "前置", trim(depends, 180));
        // 热评：标题独占一行，每条走 markdown 引用块（用户名加粗 + 赞数徽章，已在 hotComments 拼好）
        if (!hot.isEmpty()) {
            md.line("💬 **热评**");
            for (String c : hot) md.line(c);
        }
        md.link("查看详情", e.url);
        // 正文：一条分割线后直接跟原生 markdown 正文（全量，整卡超长由 sendDetail 分段发）
        if (!intro.isEmpty()) {
            md.line("").line("---").line("").line(intro);
        }
        return md.build();
    }

    /**
     * 作者 / 开发团队。实测 DOM：{@code li.col-lg-12.author .member} 内，
     * {@code span.name a} 为成员名、{@code span.position} 为身份（所有者/成员…）。
     * 取前若干名，附身份；全空返回 ""（卡片字段自动跳过）。
     */
    private String extractAuthors(Document doc) {
        List<String> out = new ArrayList<>();
        for (Element m : doc.select("li.author .member")) {
            Element nameEl = m.selectFirst(".name a");
            if (nameEl == null) nameEl = m.selectFirst(".name");
            if (nameEl == null) continue;
            String name = nameEl.text().trim();
            if (name.isEmpty() || out.stream().anyMatch(s -> s.startsWith(name))) continue;
            String pos = textOf0(m, ".position");
            out.add(pos.isEmpty() ? name : name + "(" + pos + ")");
            if (out.size() >= 6) break;
        }
        // 兜底：老结构/无 .member 时，直接取作者链接文本
        if (out.isEmpty()) {
            for (Element a : doc.select("li.author a[href*=/author/], li.author a[href*=/center.mcmod.cn/]")) {
                String t = a.text().trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
                if (out.size() >= 6) break;
            }
        }
        return String.join(" / ", out);
    }

    /**
     * 前置 / 依赖模组。实测 DOM：模组主页内联多个 {@code li.col-lg-12.relation}，
     * 其中 span 文案含「前置Mod」的那块，其 {@code ul li a[href*=/class/]} 即本模组的前置。
     * 需<b>排除</b>反向关系块（span 文案为「…依赖本Mod…才能运行」，那是"谁依赖它"）。
     * 全空返回 ""（卡片字段自动跳过）。全部内联，无需额外请求。
     */
    private String extractDependencies(Document doc, Entry e) {
        List<String> out = new ArrayList<>();
        for (Element block : doc.select("li.relation")) {
            Element label = block.selectFirst("span");
            String labelText = label != null ? label.text() : "";
            // 只要「前置Mod」块；反向块（"依赖…才能运行"）跳过
            if (!labelText.contains("前置")) continue;
            for (Element a : block.select("ul li a[href*=/class/]")) {
                String t = a.text().trim().replaceAll("\\s+", " ");
                if (t.length() >= 2 && !out.contains(t)) out.add(t);
                if (out.size() >= 8) break;
            }
        }
        return String.join(" / ", out);
    }

    /**
     * 模组简介 HTML → markdown（保留原生排版）。实测 .common-text 结构：
     * {@code <span class="common-text-title-1/-2">小标题</span>}、{@code <p>段落</p>}、
     * {@code <ul><li><p>条目</p></li></ul>} 列表。转成 ### / #### 标题、段落、- 列表。
     * 图片跳过（正文不塞图，避免刷屏）。空返回 ""。
     */
    private String introMarkdown(Document doc) {
        Element el = doc.selectFirst(".common-text");
        if (el == null) return "";
        StringBuilder sb = new StringBuilder();
        renderIntroNode(el, sb);
        // 清理：解码残留实体已由 jsoup text() 处理；压掉 3+ 连续空行
        return sb.toString().replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private void renderIntroNode(org.jsoup.nodes.Node node, StringBuilder sb) {
        for (org.jsoup.nodes.Node c : node.childNodes()) {
            if (c instanceof org.jsoup.nodes.TextNode) {
                sb.append(((org.jsoup.nodes.TextNode) c).text());
            } else if (c instanceof Element) {
                Element e = (Element) c;
                String tag = e.tagName();
                if (e.hasClass("common-text-title-1")) {
                    sb.append("\n### ").append(e.text().trim()).append("\n");
                } else if (e.hasClass("common-text-title-2")) {
                    sb.append("\n#### ").append(e.text().trim()).append("\n");
                } else if ("li".equals(tag)) {
                    // li 内容多为单个 <p>，直接取整行文本，避免内部块级换行把「- 」和正文拆成两行
                    sb.append("\n- ").append(e.text().trim());
                } else if ("p".equals(tag) || "div".equals(tag)) {
                    sb.append("\n"); renderIntroNode(e, sb); sb.append("\n");
                } else if ("br".equals(tag)) {
                    sb.append("\n");
                } else if ("img".equals(tag)) {
                    // 正文图片跳过
                } else if ("strong".equals(tag) || "b".equals(tag)) {
                    sb.append("**"); renderIntroNode(e, sb); sb.append("**");
                } else {
                    renderIntroNode(e, sb);
                }
            }
        }
    }

    /** 在给定元素范围内取选择器首个匹配的文本（Element 版 textOf）。 */
    private static String textOf0(Element scope, String selector) {
        Element el = scope.selectFirst(selector);
        return el != null ? el.text().trim() : "";
    }

    /** 物品详情卡。 */
    private String itemCardMarkdown(Document doc, Entry e) {
        // 标题：收紧到 .itemname .name h5（页面有多个 h5：关于/关注百科等，旧的 .itemname h5 会误抓）
        String full = textOf(doc, ".itemname .name h5");
        if (full.isEmpty()) full = textOf(doc, ".itemname h5");
        String cname = full, ename = "";
        int p = full.indexOf(" (");
        if (p > 0) {
            cname = full.substring(0, p).trim();
            ename = full.substring(p + 2).replace(")", "").trim();
        }
        if (cname.isEmpty()) cname = e.cname;
        String fromMod = textOf(doc, "a[href~=/class/\\d+\\.html]");
        // 物品属性表：按「键：」取相邻单元格值（实测键名带全角/半角冒号）
        String ename2 = tdValue(doc, "次要名称");
        String maxStack = tdValue(doc, "最大堆叠");
        String category = tdValue(doc, "资料分类");
        // 次要名称若与标题括号里的英文名相同则不重复展示
        if (!ename2.isEmpty() && ename2.equalsIgnoreCase(ename)) ename2 = "";

        return Md.card("🧱", cname + (ename.isEmpty() ? "" : " · " + ename))
                .field("📦", "来自模组", fromMod)
                .field("🗂", "分类", category)
                .field("🔢", "最大堆叠", maxStack)
                .field("🔤", "次要名称", ename2)
                .link("查看物品", e.url)
                .build();
    }

    /** 物品属性表：找文本以「键：」或「键:」开头的 td，取其相邻 td 的文本；无则返回 ""。 */
    private static String tdValue(Document doc, String key) {
        for (Element td : doc.select("td")) {
            String t = td.text().trim();
            if (t.startsWith(key + "：") || t.startsWith(key + ":") || t.equals(key)) {
                Element next = td.nextElementSibling();
                if (next != null) return next.text().trim();
                // 键值同格（"键：值"）时剥前缀
                String v = strip(t, key);
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    /** 教程等通用卡（标题 + 简介）。 */
    private String genericCardMarkdown(Document doc, Entry e) {
        String title = firstNonEmpty(
                textOf(doc, ".class-title h3"), textOf(doc, "h1"),
                textOf(doc, ".postname h5"), e.cname);
        String intro = firstNonEmpty(textOf(doc, ".common-text"), textOf(doc, ".text"), e.desc);
        return Md.card("📖", title)
                .quote(trim(intro, 160))
                .link("查看", e.url)
                .build();
    }

    // ==================== 热评 ====================

    /**
     * 取该条目点赞最高的前 {@link #HOT_COMMENTS} 条短评（热评），按赞数降序。失败/无评论返回空列表。
     * 评论是单独的 AJAX：POST /frame/comment/CommentRow/，data={type,channel=1,doid,page=1,selfonly=0}，
     * 返回 JSON，row[] 每条有 user.name / content(HTML) / attitude.up。
     */
    private List<String> hotComments(Entry e) {
        List<String> out = new ArrayList<>();
        try {
            String id = e.url.replaceAll(".*/(\\d+)\\.html.*", "$1");
            if (!id.matches("\\d+")) return out;
            String ctype = e.type.prefix.replace("/", ""); // class / item / modpack / post
            String json = "{\"type\":\"" + ctype + "\",\"channel\":\"1\",\"doid\":\"" + id
                    + "\",\"page\":1,\"selfonly\":0}";
            Http.Response resp = Http.post("https://www.mcmod.cn/frame/comment/CommentRow/")
                    .form("data=" + URLEncoder.encode(json, "UTF-8"))
                    .header("User-Agent", UA)
                    .header("Referer", e.url)
                    .header("Origin", "https://www.mcmod.cn")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", cookie)
                    .timeout(10000, 20000)
                    .send();
            if (resp.code != 200 || resp.body == null) {
                debug("热评请求 HTTP " + resp.code + "（doid=" + id + "），跳过热评");
                return out;
            }
            JsonObject root = JsonParser.parseString(resp.body).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject()) return out;
            JsonArray rows = root.getAsJsonObject("data").getAsJsonArray("row");
            if (rows == null) return out;
            // 收集全部 (赞, 格式化文本)，按赞降序取前 N
            List<int[]> ups = new ArrayList<>();           // [up, index]
            List<String> formatted = new ArrayList<>();
            for (JsonElement el : rows) {
                JsonObject row = el.getAsJsonObject();
                int up = 0;
                if (row.has("attitude") && row.get("attitude").isJsonObject()) {
                    JsonElement u = row.getAsJsonObject("attitude").get("up");
                    if (u != null && u.isJsonPrimitive()) up = u.getAsInt();
                }
                String text = Jsoup.parse(optStr(row, "content")).text().trim();
                if (text.isEmpty()) continue;
                String name = row.has("user") ? optStr(row.getAsJsonObject("user"), "name") : "";
                ups.add(new int[]{up, formatted.size()});
                // 引用块风：> **用户名**：正文 `👍N`（用户名加粗，赞数走代码块小徽章）
                formatted.add("> " + (name.isEmpty() ? "" : "**" + name + "**：")
                        + trim(text, COMMENT_MAX) + (up > 0 ? " `👍" + up + "`" : ""));
            }
            ups.sort((a, b) -> Integer.compare(b[0], a[0]));  // 赞降序
            for (int i = 0; i < ups.size() && out.size() < HOT_COMMENTS; i++) {
                out.add(formatted.get(ups.get(i)[1]));
            }
        } catch (Exception ex) {
            warn("热评失败: " + ex.getMessage());
        }
        return out;
    }

    private static String optStr(JsonObject o, String key) {
        return (o != null && o.has(key) && o.get(key).isJsonPrimitive()) ? o.get(key).getAsString() : "";
    }

    // ==================== 诊断 ====================

    public String testConnection() {
        Page r = search("create", Type.MOD, 1);
        if (!r.entries.isEmpty()) return "✅ MCMOD 可用（cookie 有效），示例: " + r.entries.get(0).title;
        return "❌ MCMOD 不可用：被验证码拦或 cookie 失效，请更新 config 的 mcmod-cookie。";
    }

    // ==================== HTTP / 工具 ====================

    private String fetch(String url, String referer) {
        long t0 = System.currentTimeMillis();
        // cookie 不打全文（含登录态/隐私），只报是否携带 + 长度。
        debug("GET " + url + "  (referer=" + referer + ", cookie=" + (cookie.isEmpty() ? "无" : cookie.length() + " 字符") + ")");
        try {
            Http.Response resp = Http.get(url)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Cookie", cookie)
                    .timeout(10000, 20000)
                    .send();
            int len = resp.body == null ? 0 : resp.body.length();
            debug("← HTTP " + resp.code + ", body " + len + " 字节, 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            if (resp.code != 200) {
                warn("HTTP " + resp.code + "（cookie 可能失效或被反爬拦）: " + url);
                return null;
            }
            if (resp.body != null && resp.body.contains("安全验证") && resp.body.contains("captcha")) {
                warn("被图片验证码拦截，请更新 config 的 mcmod-cookie（从浏览器 F12 复制）。");
                return null;
            }
            return resp.body;
        } catch (Exception e) {
            warn("请求失败: " + e.getMessage());
            return null;
        }
    }

    private static String textOf(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        return el != null ? el.text().trim() : "";
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String failMsg(String name) {
        return "❌ 获取「" + name + "」详情失败（可能 cookie 失效，请更新 mcmod-cookie）。";
    }

    private static String normalize(String href) {
        if (href == null) return "";
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return "https://www.mcmod.cn" + href;
        return href;
    }

    private static String strip(String s, String prefix) {
        s = s.trim();
        if (s.startsWith(prefix)) s = s.substring(prefix.length());
        if (s.startsWith(":") || s.startsWith("：")) s = s.substring(1);
        return s.trim();
    }

    private static String join(String sep, String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + sep + b;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void warn(String msg) {
        if (logger != null) logger.warn("[MCMOD] " + msg);
    }

    /** 调试日志：仅当框架调试模式（核心 config 的 debug）开启时输出，跟随框架开关。 */
    private void debug(String msg) {
        if (logger != null && org.windy.xingtubot.common.api.DebugFlag.isOn()) {
            logger.info("[MCMOD][debug] " + msg);
        }
    }
}
