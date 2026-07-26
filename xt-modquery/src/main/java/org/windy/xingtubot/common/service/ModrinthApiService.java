package org.windy.xingtubot.common.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.util.Http;
import org.windy.xingtubot.common.util.Md;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Modrinth API 客户端：模组/整合包搜索与详情。
 * <p>
 * API 文档：https://docs.modrinth.com/
 * 免费无需 key，速率限制 300 次/分钟。
 */
public class ModrinthApiService {

    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String CURSEFORGE_API = "https://api.curseforge.com";
    private static final int CURSEFORGE_GAME_ID = 432; // Minecraft

    private static Proxy proxy = Proxy.NO_PROXY;
    private final BotLogger logger;
    private Translator translator;
    private org.windy.xingtubot.common.ai.AiService llm;
    private String curseforgeApiKey = "";
    private java.util.Map<String, String> aliases = java.util.Collections.emptyMap();

    public ModrinthApiService(BotLogger logger) {
        this.logger = logger;
    }

    public void setTranslator(Translator translator) {
        this.translator = translator;
    }

    public void setLlm(org.windy.xingtubot.common.ai.AiService llm) {
        this.llm = llm;
    }

    public void setAliases(java.util.Map<String, String> aliases) {
        this.aliases = aliases != null ? aliases : java.util.Collections.emptyMap();
    }

    /**
     * 设置 CurseForge API Key（从 config 加载）。
     * 申请地址：https://console.curseforge.com/#/api-keys
     */
    public void setCurseforgeApiKey(String key) {
        this.curseforgeApiKey = key != null ? key.trim() : "";
    }

    public boolean hasCurseforgeKey() {
        return !curseforgeApiKey.isEmpty();
    }

    // ==================== 数据模型 ====================

    public static class ProjectDetail {
        public final String slug;
        public final String title;
        public final String description;
        public final String body;        // 详细正文（Markdown）
        public final String author;
        public final String iconUrl;
        public final String projectUrl;
        public final int downloads;
        public final String projectType;
        public final List<String> categories;
        public final List<String> gameVersions;
        public final List<String> loaders;
        public final List<String> gallery; // 展示图片 URL 列表
        public final String sourceUrl;
        public final String wikiUrl;
        public final String dateModified;

        public ProjectDetail(String slug, String title, String description, String body,
                             String author, String iconUrl, String projectUrl, int downloads,
                             String projectType, List<String> categories,
                             List<String> gameVersions, List<String> loaders,
                             List<String> gallery,
                             String sourceUrl, String wikiUrl, String dateModified) {
            this.slug = slug;
            this.title = title;
            this.description = description;
            this.gallery = gallery != null ? gallery : java.util.Collections.emptyList();
            this.body = body;
            this.author = author;
            this.iconUrl = iconUrl;
            this.projectUrl = projectUrl;
            this.downloads = downloads;
            this.projectType = projectType;
            this.categories = categories;
            this.gameVersions = gameVersions;
            this.loaders = loaders;
            this.sourceUrl = sourceUrl;
            this.wikiUrl = wikiUrl;
            this.dateModified = dateModified;
        }
    }

    // ==================== 搜索 ====================

    /**
     * 搜索模组或整合包。Modrinth 无结果时自动回退 CurseForge。
     *
     * @param keyword     搜索关键词
     * @param projectType "mod" / "modpack" / null（全部）
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String keyword, String projectType) {
        String searchKeyword = keyword;

        // 中文关键词 → 翻译 + CFPA 索引匹配
        if (containsChinese(keyword)) {
            searchKeyword = resolveChineseKeyword(keyword);
        }

        // ① 搜 Modrinth
        List<SearchResult> list = searchModrinth(searchKeyword, projectType);
        if (!list.isEmpty()) return list;

        // ② 用原词再试（有些中文音译就是 mod 名）
        if (!searchKeyword.equals(keyword)) {
            list = searchModrinth(keyword, projectType);
            if (!list.isEmpty()) return list;
        }

        // ③ CurseForge 兜底
        if (!curseforgeApiKey.isEmpty()) {
            List<SearchResult> cf = searchCurseforge(searchKeyword, projectType);
            if (!cf.isEmpty()) {
                info("[Search] Modrinth 无结果，CurseForge 返回 " + cf.size() + " 条");
                return cf;
            }
        }

        return list;
    }

    /**
     * 中文关键词解析：别名 → LLM → 百度翻译 → 返回英文搜索词。
     */
    private String resolveChineseKeyword(String chineseKeyword) {
        String key = chineseKeyword.trim().toLowerCase();

        // ① 手动别名（精确命中，零成本）
        String alias = aliases.get(key);
        if (alias != null && !alias.isEmpty()) {
            info("[Search] 别名命中: '" + chineseKeyword + "' → '" + alias + "'");
            return alias;
        }

        // ② LLM 翻译（理解模组上下文）
        if (llm != null) {
            String llmResult = askLlm(chineseKeyword);
            if (llmResult != null && !llmResult.isEmpty()) {
                info("[Search] LLM 翻译: '" + chineseKeyword + "' → '" + llmResult + "'");
                return llmResult;
            }
        }

        // ③ 百度翻译兜底
        if (translator != null && translator.isEnabled()) {
            String translated = translator.translateZhToEn(chineseKeyword).trim();
            if (!translated.isEmpty() && !translated.equals(chineseKeyword)) {
                info("[Search] 百度翻译: '" + chineseKeyword + "' → '" + translated + "'");
                return translated;
            }
        }

        return chineseKeyword;
    }

    /**
     * 用 LLM 把中文模组名翻译成英文 slug。
     * 返回 null 表示 LLM 未启用或调用失败。
     */
    private String askLlm(String chineseName) {
        try {
            String prompt = "你是一个Minecraft模组专家。用户输入了一个中文模组名，请返回对应的英文Modrinth slug（小写，用-分隔）。"
                    + "只返回slug本身，不要任何解释。如果不是模组名，返回原文。\n\n"
                    + "示例：\n创造 → create\n拔刀剑 → slashblade\n植物魔法 → botania\n工业 → industrial-craft\n暮色森林 → twilight-forest\n\n"
                    + "问题：" + chineseName;
            String result = llm.chat(prompt).trim().toLowerCase();
            // 清理 LLM 可能带的多余内容
            result = result.replaceAll("[^a-z0-9\\-]", "");
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            warn("[Search] LLM 调用失败: " + e.getMessage());
            return null;
        }
    }

    private static boolean containsChinese(String s) {
        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private List<SearchResult> searchModrinth(String keyword, String projectType) {
        List<SearchResult> list = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, "UTF-8");
            String facets = "";
            if (projectType != null) {
                facets = "&facets=%5B%5B%22project_type%3A" + URLEncoder.encode(projectType, "UTF-8") + "%22%5D%5D";
            }
            String url = API_BASE + "/search?query=" + encoded + facets + "&limit=10";
            String json = fetchJson(url);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray hits = root.getAsJsonArray("hits");

            for (JsonElement el : hits) {
                JsonObject h = el.getAsJsonObject();
                String slug = optStr(h, "slug");
                String title = optStr(h, "title");
                String desc = optStr(h, "description");
                String iconUrl = optStr(h, "icon_url");
                int downloads = h.has("downloads") ? h.get("downloads").getAsInt() : 0;
                String pType = optStr(h, "project_type");
                String author = optStr(h, "author");

                List<String> cats = new ArrayList<>();
                if (h.has("categories") && h.get("categories").isJsonArray()) {
                    for (JsonElement c : h.getAsJsonArray("categories")) {
                        cats.add(c.getAsString());
                    }
                }

                list.add(new SearchResult(slug, title, desc, author, iconUrl, downloads, pType, cats));
            }
        } catch (Exception e) {
            warn("[Modrinth] 搜索失败: keyword='" + keyword + "', error=" + e.getMessage());
        }
        return list;
    }

    /**
     * CurseForge 搜索（mod 或 modpack）。
     * classId: 6=mods, 4471=modpacks, 不传=全部
     */
    private List<SearchResult> searchCurseforge(String keyword, String projectType) {
        List<SearchResult> list = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(keyword, "UTF-8");
            String classId = "";
            if ("mod".equals(projectType)) classId = "&classId=6";
            else if ("modpack".equals(projectType)) classId = "&classId=4471";

            String url = CURSEFORGE_API + "/v1/mods/search?gameId=" + CURSEFORGE_GAME_ID
                    + "&searchFilter=" + encoded + classId + "&pageSize=10&sortField=2&sortOrder=desc";
            String json = fetchJsonCurseforge(url);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");

            for (JsonElement el : data) {
                JsonObject d = el.getAsJsonObject();
                String id = String.valueOf(d.get("id").getAsInt());
                String title = optStr(d, "name");
                String desc = optStr(d, "summary");
                int downloads = d.has("downloadCount") ? d.get("downloadCount").getAsInt() : 0;
                String pType = d.has("classId") ? cfClassToType(d.get("classId").getAsInt()) : "mod";

                String iconUrl = "";
                if (d.has("logo") && d.get("logo").isJsonObject()) {
                    iconUrl = optStr(d.getAsJsonObject("logo"), "thumbnailUrl");
                }

                String author = "";
                if (d.has("authors") && d.get("authors").isJsonArray()) {
                    JsonArray authors = d.getAsJsonArray("authors");
                    if (authors.size() > 0) {
                        author = optStr(authors.get(0).getAsJsonObject(), "name");
                    }
                }

                List<String> cats = new ArrayList<>();
                if (d.has("categories") && d.get("categories").isJsonArray()) {
                    for (JsonElement c : d.getAsJsonArray("categories")) {
                        cats.add(optStr(c.getAsJsonObject(), "name"));
                    }
                }

                // slug 用 cf: 前缀标识来源
                list.add(new SearchResult("cf:" + id, title, desc, author, iconUrl, downloads, pType, cats));
            }
        } catch (Exception e) {
            warn("[CurseForge] 搜索失败: keyword='" + keyword + "', error=" + e.getMessage());
        }
        return list;
    }

    private String cfClassToType(int classId) {
        if (classId == 4471) return "modpack";
        return "mod";
    }

    // ==================== 详情 ====================

    /**
     * 获取项目详情。slug 以 "cf:" 开头走 CurseForge，否则走 Modrinth。
     */
    public ProjectDetail getDetail(String slug) {
        if (slug != null && slug.startsWith("cf:")) {
            return getDetailCurseforge(slug.substring(3));
        }
        return getDetailModrinth(slug);
    }

    private ProjectDetail getDetailModrinth(String slug) {
        try {
            String url = API_BASE + "/project/" + slug;
            String json = fetchJson(url);
            JsonObject r = JsonParser.parseString(json).getAsJsonObject();

            String title = optStr(r, "title");
            String desc = optStr(r, "description");
            String body = optStr(r, "body");
            String author = optStr(r, "author");
            String iconUrl = optStr(r, "icon_url");
            String projectUrl = "https://modrinth.com/" + optStr(r, "project_type") + "/" + slug;
            int downloads = r.has("downloads") ? r.get("downloads").getAsInt() : 0;
            String pType = optStr(r, "project_type");
            String sourceUrl = optStr(r, "source_url");
            String wikiUrl = optStr(r, "wiki_url");
            String dateModified = optStr(r, "date_modified");
            if (dateModified.length() > 10) dateModified = dateModified.substring(0, 10);

            List<String> categories = new ArrayList<>();
            if (r.has("categories") && r.get("categories").isJsonArray()) {
                for (JsonElement c : r.getAsJsonArray("categories")) categories.add(c.getAsString());
            }
            List<String> gameVersions = new ArrayList<>();
            if (r.has("game_versions") && r.get("game_versions").isJsonArray()) {
                for (JsonElement v : r.getAsJsonArray("game_versions")) gameVersions.add(v.getAsString());
            }
            List<String> loaders = new ArrayList<>();
            if (r.has("loaders") && r.get("loaders").isJsonArray()) {
                for (JsonElement l : r.getAsJsonArray("loaders")) loaders.add(l.getAsString());
            }

            // 画廊图片（排除与 icon 重复的）
            List<String> gallery = new ArrayList<>();
            if (r.has("gallery") && r.get("gallery").isJsonArray()) {
                for (JsonElement g : r.getAsJsonArray("gallery")) {
                    if (g.isJsonObject()) {
                        String imgUrl = optStr(g.getAsJsonObject(), "url");
                        if (!imgUrl.isEmpty() && !imgUrl.equals(iconUrl)) gallery.add(imgUrl);
                    }
                }
            }

            return new ProjectDetail(slug, title, desc, body, author, iconUrl, projectUrl,
                    downloads, pType, categories, gameVersions, loaders, gallery, sourceUrl, wikiUrl, dateModified);
        } catch (Exception e) {
            warn("[Modrinth] 获取详情失败: slug='" + slug + "', error=" + e.getMessage());
            return null;
        }
    }

    private ProjectDetail getDetailCurseforge(String modId) {
        try {
            String url = CURSEFORGE_API + "/v1/mods/" + modId;
            String json = fetchJsonCurseforge(url);
            JsonObject r = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("data");

            String title = optStr(r, "name");
            String desc = optStr(r, "summary");
            String body = optStr(r, "description"); // 详细正文
            String slug = optStr(r, "slug");
            int downloads = r.has("downloadCount") ? r.get("downloadCount").getAsInt() : 0;
            int classId = r.has("classId") ? r.get("classId").getAsInt() : 6;
            String pType = cfClassToType(classId);
            String projectUrl = optStr(r, "links.websiteUrl");
            if (projectUrl.isEmpty()) projectUrl = "https://www.curseforge.com/minecraft/" + ("modpack".equals(pType) ? "modpacks" : "mods") + "/" + slug;
            String sourceUrl = optStr(r, "links.sourceUrl");
            String wikiUrl = optStr(r, "links.wikiUrl");
            String dateModified = optStr(r, "dateModified");
            if (dateModified.length() > 10) dateModified = dateModified.substring(0, 10);

            String iconUrl = "";
            if (r.has("logo") && r.get("logo").isJsonObject()) {
                iconUrl = optStr(r.getAsJsonObject("logo"), "thumbnailUrl");
            }

            String author = "";
            if (r.has("authors") && r.get("authors").isJsonArray()) {
                JsonArray authors = r.getAsJsonArray("authors");
                if (authors.size() > 0) author = optStr(authors.get(0).getAsJsonObject(), "name");
            }

            List<String> categories = new ArrayList<>();
            if (r.has("categories") && r.get("categories").isJsonArray()) {
                for (JsonElement c : r.getAsJsonArray("categories")) {
                    categories.add(optStr(c.getAsJsonObject(), "name"));
                }
            }

            // CurseForge 不直接返回 game_versions/loaders，从 latestFiles 推断
            List<String> gameVersions = new ArrayList<>();
            List<String> loaders = new ArrayList<>();
            if (r.has("latestFiles") && r.get("latestFiles").isJsonArray()) {
                for (JsonElement f : r.getAsJsonArray("latestFiles")) {
                    JsonObject file = f.getAsJsonObject();
                    if (file.has("gameVersions") && file.get("gameVersions").isJsonArray()) {
                        for (JsonElement gv : file.getAsJsonArray("gameVersions")) {
                            String v = gv.getAsString();
                            if (!gameVersions.contains(v)) gameVersions.add(v);
                        }
                    }
                }
            }

            // 截图（排除与 icon 重复的）
            List<String> gallery = new ArrayList<>();
            if (r.has("screenshots") && r.get("screenshots").isJsonArray()) {
                for (JsonElement s : r.getAsJsonArray("screenshots")) {
                    if (s.isJsonObject()) {
                        String imgUrl = optStr(s.getAsJsonObject(), "url");
                        if (!imgUrl.isEmpty() && !imgUrl.equals(iconUrl)) gallery.add(imgUrl);
                    }
                }
            }

            return new ProjectDetail("cf:" + modId, title, desc, body, author, iconUrl,
                    projectUrl, downloads, pType, categories, gameVersions, loaders,
                    gallery, sourceUrl, wikiUrl, dateModified);
        } catch (Exception e) {
            warn("[CurseForge] 获取详情失败: modId=" + modId + ", error=" + e.getMessage());
            return null;
        }
    }

    // ==================== 格式化 ====================

    /**
     * 格式化搜索结果列表（纯文本，简介翻译）。
     */
    public String formatSearchResults(List<SearchResult> results, String keyword) {
        // 批量翻译简介
        java.util.List<String> descs = new java.util.ArrayList<>();
        for (SearchResult r : results) {
            String d = (r.description != null && !r.description.isEmpty()) ? r.description : "";
            if (d.length() > 80) d = d.substring(0, 80) + "…";
            descs.add(d);
        }
        if (translator != null && translator.isEnabled()) {
            descs = translator.batchTranslateEnToZh(descs);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("※ 找到 ").append(results.size()).append(" 个与「").append(keyword).append("」相关的结果：\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String typeIcon = "modpack".equals(r.projectType) ? "📦整合包" : "⚙️模组";
            sb.append(String.format("%d. %s %s", i + 1, typeIcon, r.title));
            if (!r.author.isEmpty()) sb.append(" (👤 ").append(r.author).append(")");
            sb.append("\n");
            String desc = (i < descs.size() && descs.get(i) != null) ? descs.get(i) : "";
            if (!desc.isEmpty()) {
                sb.append("   ").append(desc).append("\n");
            }
        }
        sb.append("\n※ 请回复序号查看详情（60秒内有效）");
        return sb.toString();
    }

    /**
     * 格式化搜索结果列表（Markdown，带图标，简介翻译）。
     */
    public String formatSearchResultsMarkdown(List<SearchResult> results, String keyword) {
        // 批量翻译简介（1 次 API 调用）
        java.util.List<String> descs = new java.util.ArrayList<>();
        for (SearchResult r : results) {
            String d = (r.description != null && !r.description.isEmpty()) ? r.description : "";
            if (d.length() > 80) d = d.substring(0, 80) + "…";
            descs.add(d);
        }
        if (translator != null && translator.isEnabled()) {
            descs = translator.batchTranslateEnToZh(descs);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 🔍 ").append(keyword).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String typeIcon = "modpack".equals(r.projectType) ? "📦 整合包" : "⚙️ 模组";
            // 搜索列表不嵌图标：QQ 原生 markdown 图片必须带像素尺寸，而 Modrinth 图标 URL 不含尺寸；
            // 且逐条嵌图会很臃肿。列表保持文字，图片只在详情卡按方形默认尺寸嵌。
            sb.append("**").append(i + 1).append(". ").append(r.title).append("** ").append(typeIcon).append("\n");
            if (!r.author.isEmpty()) sb.append("> 👤 作者: ").append(r.author);
            sb.append("　⬇️ 下载: ").append(formatDownloads(r.downloads)).append("\n");
            String desc = (i < descs.size() && descs.get(i) != null) ? descs.get(i) : "";
            if (!desc.isEmpty()) {
                sb.append("> ").append(desc).append("\n");
            }
            sb.append("\n");
        }
        sb.append("回复序号查看详情（60秒内有效）");
        return sb.toString();
    }

    /**
     * 格式化详情（纯文本）。
     */
    public String formatDetailText(ProjectDetail d) {
        String typeLabel = "modpack".equals(d.projectType) ? "整合包" : "模组";
        StringBuilder sb = new StringBuilder();
        sb.append("📋 ").append(d.title).append("（").append(typeLabel).append("）\n");
        sb.append("作者: ").append(d.author).append("\n");
        if (!d.categories.isEmpty()) sb.append("分类: ").append(String.join(", ", d.categories)).append("\n");
        sb.append("下载量: ").append(formatDownloads(d.downloads)).append("\n");
        if (!d.gameVersions.isEmpty()) {
            sb.append("支持版本: ").append(joinVersions(d.gameVersions)).append("\n");
        }
        if (!d.loaders.isEmpty()) sb.append("加载器: ").append(String.join(", ", d.loaders)).append("\n");
        if (!d.dateModified.isEmpty()) sb.append("更新时间: ").append(d.dateModified).append("\n");
        sb.append("\n简介: ").append(d.description).append("\n");
        if (!d.sourceUrl.isEmpty()) sb.append("源码: ").append(d.sourceUrl).append("\n");
        if (!d.wikiUrl.isEmpty()) sb.append("Wiki: ").append(d.wikiUrl).append("\n");
        sb.append("🔗 ").append(d.projectUrl);
        return sb.toString();
    }

    /**
     * 格式化详情（Markdown 卡片，正文保留原始 Markdown 格式）。
     */
    // QQ 原生 markdown 图片默认尺寸（Modrinth URL 不含尺寸，用合理默认；尺寸是强制的，缺了会 40034011）。
    private static final int ICON_PX = 128;       // 图标方形
    private static final int SHOT_W = 480, SHOT_H = 270; // 截图按 16:9 默认

    /** 单条 markdown 详情卡（图标 + 元数据 + 链接，不含正文/画廊）——供分段发送做首条。 */
    private String buildMetaCard(ProjectDetail d) {
        String emoji = "modpack".equals(d.projectType) ? "📦" : "⚙️";
        StringBuilder sb = new StringBuilder();
        if (d.iconUrl != null && !d.iconUrl.isEmpty()) {
            // 图片嵌进 markdown 且必须带像素尺寸 ![alt #Wpx #Hpx](url)
            sb.append("![图标 #").append(ICON_PX).append("px #").append(ICON_PX).append("px](")
                    .append(d.iconUrl).append(")\n\n");
        }
        Md card = Md.card(emoji, d.title)
                .field("📌", "类型", "modpack".equals(d.projectType) ? "整合包" : "模组")
                .field("👤", "作者", d.author)
                .field("⬇️", "下载", formatDownloads(d.downloads))
                .field("🕐", "更新", d.dateModified)
                .field("🏷️", "分类", d.categories.isEmpty() ? "" : String.join(" / ", d.categories))
                .field("🎮", "支持", d.gameVersions.isEmpty() ? "" : joinVersions(d.gameVersions))
                .field("🔧", "加载器", d.loaders.isEmpty() ? "" : String.join(" / ", d.loaders));
        if (!d.sourceUrl.isEmpty()) card.link("📄 源码", d.sourceUrl);
        if (!d.wikiUrl.isEmpty()) card.link("📖 Wiki", d.wikiUrl);
        card.link("🔗 在 Modrinth 查看", d.projectUrl);
        return sb.append(card.build()).toString();
    }

    /**
     * 发送模组/整合包完整详情（不截断，超长自动<b>分段多条</b>发，画廊内嵌正文）。
     * 元数据卡片 + 正文 + 画廊图片合并为一个字符串，再统一分段，
     * 避免多条独立消息被 QQ 打乱顺序。
     *
     * <p>正文不做字数上限——内容多长就发多长，只在超过单条限时切成多条，
     * 从而既不丢内容、也不会因单条超 QQ 硬限而整条发送失败。
     * markdown 关时降级为纯文本框线 + 去 markdown 正文。
     */
    public void sendDetail(org.windy.xingtubot.common.event.BotMessageContext event,
                           ProjectDetail d, boolean markdownEnabled) {
        if (markdownEnabled) {
            // ---- Markdown 模式：元数据卡 + 正文 + 画廊 合并 ----
            StringBuilder combined = new StringBuilder();

            // 1. 元数据卡（图标 + 字段 + 链接）
            combined.append(buildMetaCard(d));

            // 2. 正文
            String body = pickBody(d);
            if (!body.isEmpty()) {
                if (translator != null && translator.isEnabled()) {
                    body = translator.translateEnToZh(body);
                }
                body = cleanBodyForMarkdown(body);
                if (!body.isEmpty()) {
                    combined.append("\n").append(body);
                }
            }

            // 3. 画廊图片内嵌到正文末尾
            if (d.gallery != null && !d.gallery.isEmpty()) {
                combined.append("\n\n## 🖼️ 画廊\n");
                for (int i = 0; i < d.gallery.size(); i++) {
                    combined.append("\n![截图").append(i + 1)
                            .append(" #").append(SHOT_W).append("px #").append(SHOT_H)
                            .append("px](").append(d.gallery.get(i)).append(")\n");
                }
            }

            // 统一分段发送，段间延迟保证顺序
            List<String> chunks = splitText(combined.toString(), DETAIL_CHUNK);
            for (int i = 0; i < chunks.size(); i++) {
                if (i > 0) {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                event.replyMarkdown(chunks.get(i), null);
            }
        } else {
            // ---- 纯文本模式 ----
            event.reply(formatDetailMeta(d));

            String body = pickBody(d);
            if (!body.isEmpty()) {
                if (translator != null && translator.isEnabled()) {
                    body = translator.translateEnToZh(body);
                }
                body = cleanBodyForChat(body);
                List<String> chunks = splitText(body, DETAIL_CHUNK);
                for (int i = 0; i < chunks.size(); i++) {
                    if (i > 0) {
                        try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    event.reply(chunks.get(i));
                }
            }

            if (d.gallery != null && !d.gallery.isEmpty()) {
                StringBuilder gb = new StringBuilder();
                gb.append("🖼️ 画廊截图：\n");
                for (int i = 0; i < d.gallery.size(); i++) {
                    gb.append(i + 1).append(". ").append(d.gallery.get(i)).append("\n");
                }
                event.reply(gb.toString());
            }
        }
    }

    /** 正文分段单条上限（字符）。QQ 群 markdown 单条有服务端硬限，取保守值避免超限失败。 */
    private static final int DETAIL_CHUNK = 1500;

    /** 按最大长度分段，尽量在换行处断开（避免切断 markdown 语法行）。 */
    private static List<String> splitText(String text, int maxLen) {
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

    /**
     * 格式化详情（纯文本 + 翻译）。
     */
    public String formatDetailTextChinese(ProjectDetail d) {
        String typeLabel = "modpack".equals(d.projectType) ? "📦 整合包" : "⚙️ 模组";
        StringBuilder sb = new StringBuilder();

        // 图标
        if (d.iconUrl != null && !d.iconUrl.isEmpty()) {
            sb.append(d.iconUrl).append("\n\n");
        }

        sb.append("┌ ").append(d.title).append("\n");
        sb.append("├ ").append(typeLabel);
        if (!d.author.isEmpty()) sb.append(" · 👤 作者: ").append(d.author);
        sb.append("\n");
        sb.append("├ ⬇️ 下载量: ").append(formatDownloads(d.downloads));
        if (!d.dateModified.isEmpty()) sb.append(" · 🕐 更新: ").append(d.dateModified.substring(0, 10));
        sb.append("\n");
        if (!d.categories.isEmpty()) {
            sb.append("├ 🏷️ 分类: ").append(String.join(", ", d.categories)).append("\n");
        }
        if (!d.gameVersions.isEmpty()) {
            sb.append("├ 🎮 支持版本: ").append(joinVersions(d.gameVersions)).append("\n");
        }
        if (!d.loaders.isEmpty()) {
            sb.append("├ 🔧 加载器: ").append(String.join(", ", d.loaders)).append("\n");
        }

        String body = pickBody(d);
        if (!body.isEmpty()) {
            if (translator != null && translator.isEnabled()) {
                body = translator.translateEnToZh(body);
            }
            body = cleanBodyForChat(body);
            if (body.length() > 800) body = body.substring(0, 800) + "\n…";
            sb.append("├\n");
            sb.append("├ ").append(body.replace("\n", "\n│ ")).append("\n");
        }

        // 画廊图片
        if (d.gallery != null && !d.gallery.isEmpty()) {
            int show = Math.min(d.gallery.size(), 3);
            sb.append("├\n");
            for (int i = 0; i < show; i++) {
                sb.append("├ 🖼️ ").append(d.gallery.get(i)).append("\n");
            }
        }

        sb.append("└ 🔗 ").append(d.projectUrl);
        return sb.toString();
    }

    /**
     * 格式化元数据卡片（不含正文，用于分段发送的第一条）。
     */
    public String formatDetailMeta(ProjectDetail d) {
        String typeLabel = "modpack".equals(d.projectType) ? "📦 整合包" : "⚙️ 模组";
        StringBuilder sb = new StringBuilder();

        sb.append("┌ ").append(d.title).append("\n");
        sb.append("├ ").append(typeLabel);
        if (!d.author.isEmpty()) sb.append(" · 👤 作者: ").append(d.author);
        sb.append("\n");
        sb.append("├ ⬇️ 下载量: ").append(formatDownloads(d.downloads));
        if (!d.dateModified.isEmpty()) sb.append(" · 🕐 更新: ").append(d.dateModified.substring(0, 10));
        sb.append("\n");
        if (!d.categories.isEmpty()) {
            sb.append("├ 🏷️ 分类: ").append(String.join(", ", d.categories)).append("\n");
        }
        if (!d.gameVersions.isEmpty()) {
            sb.append("├ 🎮 支持版本: ").append(joinVersions(d.gameVersions)).append("\n");
        }
        if (!d.loaders.isEmpty()) {
            sb.append("├ 🔧 加载器: ").append(String.join(", ", d.loaders)).append("\n");
        }
        // 短描述
        if (d.description != null && !d.description.isEmpty()) {
            String desc = d.description;
            if (desc.length() > 200) desc = desc.substring(0, 200) + "...";
            sb.append("├\n├ ").append(desc).append("\n");
        }
        // 链接
        if (!d.sourceUrl.isEmpty()) {
            sb.append("├ 📄 源码: ").append(d.sourceUrl).append("\n");
        }
        if (!d.wikiUrl.isEmpty()) {
            sb.append("├ 📖 Wiki: ").append(d.wikiUrl).append("\n");
        }
        sb.append("└ 🔗 ").append(d.projectUrl);
        return sb.toString();
    }

    /**
     * 格式化正文（不含元数据，用于分段发送的后续条）。
     * 不截断，返回完整正文（已翻译+清理）。
     */
    public String formatDetailBody(ProjectDetail d) {
        String body = pickBody(d);
        if (body.isEmpty()) return null;
        if (translator != null && translator.isEnabled()) {
            body = translator.translateEnToZh(body);
        }
        body = cleanBodyForChat(body);
        return body;
    }

    /** 优先取 body（完整正文），没有则回退 description。 */
    private String pickBody(ProjectDetail d) {
        if (d.body != null && !d.body.isEmpty()) return d.body;
        return d.description != null ? d.description : "";
    }

    /**
     * 清理 Markdown 正文（Markdown 输出用）：
     * 保留标题/粗体/列表/代码/链接等格式，只去掉 QQ 无法渲染的内容。
     */
    private String cleanBodyForMarkdown(String text) {
        if (text == null) return "";
        return text
                .replaceAll("!\\[.*?]\\(.*?\\)", "")      // 图片 ![alt](url)
                .replaceAll("<img[^>]*>", "")               // HTML img
                .replaceAll("<br\\s*/?>", "\n")              // HTML br
                .replaceAll("<details>", "")                 // HTML details
                .replaceAll("</details>", "")
                .replaceAll("<summary[^>]*>", "**")
                .replaceAll("</summary>", "**")
                .replaceAll("<[^>]+>", "")                   // 其他 HTML 标签
                .replaceAll("\n{3,}", "\n\n")                // 多余空行
                .trim();
    }

    /**
     * 清理 Markdown 正文（纯文本输出用）：去掉所有格式标记。
     */
    private String cleanBodyForChat(String text) {
        if (text == null) return "";
        return text
                .replaceAll("!\\[.*?]\\((.*?)\\)", "$1")   // 图片 ![alt](url) → 保留 url
                .replaceAll("<img[^>]*src=\"([^\"]+)\"[^>]*>", "$1") // HTML img → 保留 src
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\[([^]]+)]\\(\\1\\)", "$1")  // [text](text) → text
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1") // [text](url) → text
                .replaceAll("#{1,6}\\s*", "")
                .replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("\\|.*\\|", "")
                .replaceAll("[-=]{3,}", "")
                .replaceAll(">\\s*", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 诊断 Modrinth + CurseForge 连通性。返回诊断结果字符串。
     */
    public String testConnection() {
        StringBuilder sb = new StringBuilder();
        sb.append("📡 搜索 API 诊断\n");
        sb.append("─────────────────\n");

        // 测试 Modrinth 搜索
        long start = System.currentTimeMillis();
        try {
            String json = fetchJson(API_BASE + "/search?query=create&limit=1");
            long elapsed = System.currentTimeMillis() - start;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int total = root.has("total_hits") ? root.get("total_hits").getAsInt() : 0;
            sb.append("Modrinth 搜索: ✅ 正常 (").append(elapsed).append("ms)\n");
            sb.append("  'create' 命中 ").append(total).append(" 条\n");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            sb.append("Modrinth 搜索: ❌ 失败 (").append(elapsed).append("ms)\n");
            sb.append("  错误: ").append(e.getMessage()).append("\n");
        }

        // 测试 Modrinth 详情
        start = System.currentTimeMillis();
        try {
            String json = fetchJson(API_BASE + "/project/create");
            long elapsed = System.currentTimeMillis() - start;
            JsonObject r = JsonParser.parseString(json).getAsJsonObject();
            sb.append("Modrinth 详情: ✅ 正常 (").append(elapsed).append("ms)\n");
            sb.append("  标题: ").append(optStr(r, "title")).append("\n");
            sb.append("  描述: ").append(optStr(r, "description").substring(0, Math.min(60, optStr(r, "description").length()))).append("...\n");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            sb.append("Modrinth 详情: ❌ 失败 (").append(elapsed).append("ms)\n");
            sb.append("  错误: ").append(e.getMessage()).append("\n");
        }

        // 测试 CurseForge
        if (!curseforgeApiKey.isEmpty()) {
            start = System.currentTimeMillis();
            try {
                String json = fetchJsonCurseforge(CURSEFORGE_API + "/v1/mods/search?gameId=" + CURSEFORGE_GAME_ID + "&searchFilter=create&pageSize=1");
                long elapsed = System.currentTimeMillis() - start;
                sb.append("CurseForge: ✅ 正常 (").append(elapsed).append("ms)\n");
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                sb.append("CurseForge: ❌ 失败 (").append(elapsed).append("ms)\n");
                sb.append("  错误: ").append(e.getMessage()).append("\n");
            }
        } else {
            sb.append("CurseForge: ⚠️ 未配置 API Key\n");
        }

        // 翻译状态
        sb.append("百度翻译: ").append(translator != null && translator.isEnabled() ? "✅ 已启用" : "⚠️ 未启用").append("\n");

        // 别名数量
        sb.append("中文别名: ").append(aliases.size()).append(" 条\n");

        // LLM 状态
        sb.append("LLM 翻译: ").append(llm != null ? "✅ 已启用" : "⚠️ 未启用（llm-enable=false）").append("\n");

        sb.append("─────────────────\n");
        return sb.toString();
    }

    // ==================== HTTP ====================

    public String fetchJson(String urlPath) throws IOException {
        IOException lastExc = null;
        for (int i = 1; i <= 3; i++) {
            try {
                Http.Response resp = Http.get(urlPath).proxy(proxy)
                        .userAgent("XingtuBot/1.0 (modrinth-search)")
                        .header("Accept", "application/json")
                        .send();
                if (resp.code == 429) {
                    throw new IOException("Modrinth API 限流，请稍后再试");
                }
                if (resp.code != 200) {
                    throw new IOException("HTTP " + resp.code);
                }
                return resp.body;
            } catch (IOException e) {
                lastExc = e;
                warn("[Modrinth] 第 " + i + " 次请求失败: " + e.getMessage());
                try {
                    Thread.sleep(800L * i);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw lastExc;
    }

    /**
     * CurseForge API 请求（需要 x-api-key header）。
     */
    private String fetchJsonCurseforge(String urlPath) throws IOException {
        IOException lastExc = null;
        for (int i = 1; i <= 3; i++) {
            try {
                Http.Response resp = Http.get(urlPath).proxy(proxy)
                        .userAgent("XingtuBot/1.0 (curseforge-search)")
                        .header("Accept", "application/json")
                        .header("x-api-key", curseforgeApiKey)
                        .send();
                if (resp.code == 403) {
                    throw new IOException("CurseForge API Key 无效或未配置");
                }
                if (resp.code == 429) {
                    throw new IOException("CurseForge API 限流，请稍后再试");
                }
                if (resp.code != 200) {
                    throw new IOException("HTTP " + resp.code);
                }
                return resp.body;
            } catch (IOException e) {
                lastExc = e;
                warn("[CurseForge] 第 " + i + " 次请求失败: " + e.getMessage());
                try {
                    Thread.sleep(800L * i);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw lastExc;
    }

    // ==================== 代理 ====================

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

    // ==================== 工具 ====================

    private String optStr(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
                ? obj.get(key).getAsString() : "";
    }

    private String formatDownloads(int downloads) {
        if (downloads >= 1_000_000) return String.format("%.1fM", downloads / 1_000_000.0);
        if (downloads >= 1_000) return String.format("%.1fK", downloads / 1_000.0);
        return String.valueOf(downloads);
    }

    private String joinVersions(List<String> versions) {
        if (versions.size() <= 5) return String.join(", ", versions);
        // 只显示最近 5 个版本
        List<String> recent = versions.subList(versions.size() - 5, versions.size());
        return String.join(", ", recent) + " 等 " + versions.size() + " 个版本";
    }

    private void info(String msg) {
        if (logger != null) logger.info("[Modrinth] " + msg);
    }

    private void warn(String msg) {
        if (logger != null) logger.warn("[Modrinth] " + msg);
    }
}
