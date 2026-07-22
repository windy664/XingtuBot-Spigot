package org.windy.xingtubot.common.command.impl;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.service.McmodApiService;
import org.windy.xingtubot.common.service.McmodApiService.Entry;
import org.windy.xingtubot.common.service.McmodApiService.Page;
import org.windy.xingtubot.common.service.McmodApiService.Type;
import org.windy.xingtubot.common.service.ModrinthApiService;
import org.windy.xingtubot.common.service.SearchResult;
import org.windy.xingtubot.common.util.Keyboards;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCMOD 通用查询命令（复用 {@link McmodApiService} 轮子）：
 * <ul>
 *   <li>{@code /mod 词} 模组、{@code /item 词} 物品、{@code /pack 词} 整合包、{@code /tutorial 词} 教程；</li>
 *   <li>结果以<b>可点按钮</b>呈现（点按钮看详情，无数字冲突）；</li>
 *   <li>翻页用 <b>上一页/下一页 按钮</b>（回调重搜对应页）。</li>
 * </ul>
 * 全部走回调按钮（INTERACTION_CREATE 合成命令）：详情 {@code /mcd N}、翻页 {@code /mcp P}。
 */
public class McmodCommand implements BotCommand {

    private static final long SESSION_TTL = 300_000;
    private static final String DETAIL = "/mcd ";
    private static final String PAGE = "/mcp ";

    private final McmodApiService api;
    // 可选 Modrinth 兜底：/mod 在 mcmod 搜空时自动转 Modrinth（优先级 mcmod > modrinth）。null=不兜底。
    private volatile ModrinthApiService modrinth;

    private final Map<String, Page> sessions = new ConcurrentHashMap<>();
    // Modrinth 兜底结果会话（与 mcmod sessions 二选一，按 formId 区分当前是哪条路）
    private final Map<String, List<SearchResult>> mrSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionTime = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "McmodSessionCleaner");
        t.setDaemon(true);
        return t;
    });

    public McmodCommand(McmodApiService api) {
        this.api = api;
        cleaner.scheduleAtFixedRate(this::cleanExpired, 300_000L, 300_000L, TimeUnit.MILLISECONDS);
    }


    /** 注入 Modrinth 兜底（/mod 在 mcmod 搜空时自动转 Modrinth）。 */
    public void setModrinthFallback(ModrinthApiService modrinth) {
        this.modrinth = modrinth;
    }

    @Override
    public boolean matches(String message) {
        String m = message.trim().toLowerCase();
        return m.startsWith("/mod ") || m.startsWith("/item ") || m.startsWith("/pack ")
                || m.startsWith("/tutorial ") || m.startsWith(DETAIL) || m.startsWith(PAGE);
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String formId = event.getSenderId();
        String msg = message.trim();
        String lower = msg.toLowerCase();

        // 详情：/mcd N
        if (lower.startsWith(DETAIL)) {
            int i = parseInt(msg.substring(DETAIL.length()));
            // Modrinth 兜底会话优先
            List<SearchResult> mr = isLive(formId) ? mrSessions.get(formId) : null;
            if (mr != null && modrinth != null) {
                if (i < 0 || i >= mr.size()) { event.reply("❌ 无效项，请重新搜索。"); return; }
                SearchResult r = mr.get(i);
                event.reply("正在查询详情（Modrinth），请稍候...");
                ModrinthApiService.ProjectDetail d = modrinth.getDetail(r.slug);
                if (d == null) { event.reply("❌ 获取详情失败。"); return; }
                modrinth.sendDetail(event, d, true); // mcmod 恒 markdown；分段发送，正文/画廊全量不截断
                return;
            }
            Page p = liveSession(formId);
            if (p == null) {
             //   event.reply("⚠️ 搜索结果已过期，请重新搜索。");
                return;
            }
            if (i < 0 || i >= p.entries.size()) {
                event.reply("❌ 无效项，请重新搜索。");
                return;
            }
            Entry e = p.entries.get(i);
            event.reply("正在查询详情，请稍候...");
            api.sendDetail(e, event); // 先发封面图 + markdown 卡片
            return;
        }

        // 翻页：/mcp P（重搜同类型+关键词的指定页）
        if (lower.startsWith(PAGE)) {
            Page prev = sessions.get(formId);
            if (prev == null) {
                event.reply("⚠️ 搜索结果已过期，请重新搜索。");
                return;
            }
            int page = parseInt(msg.substring(PAGE.length()));
            if (page < 1) page = 1;
            doSearch(prev.keyword, prev.type, page, formId, event);
            return;
        }

        // 搜索：按前缀定类型
        Type type = null;
        String kw = null;
        if (lower.startsWith("/mod ")) { type = Type.MOD; kw = msg.substring(5); }
        else if (lower.startsWith("/item ")) { type = Type.ITEM; kw = msg.substring(6); }
        else if (lower.startsWith("/pack ")) { type = Type.MODPACK; kw = msg.substring(6); }
        else if (lower.startsWith("/tutorial ")) { type = Type.TUTORIAL; kw = msg.substring(10); }
        if (type == null) return;
        kw = kw.trim();
        if (kw.isEmpty()) {
            event.reply("用法：/mod 模组名 · /item 物品名 · /pack 整合包名 · /tutorial 教程名");
            return;
        }
        doSearch(kw, type, 1, formId, event);
    }

    private void doSearch(String keyword, Type type, int page, String formId, BotMessageContext event) {
        Page p = api.search(keyword, type, page);
        if (p.entries.isEmpty()) {
            // 优先级兜底：仅 /mod 第一页搜空时，自动转 Modrinth（mcmod > modrinth）
            if (type == Type.MOD && page == 1 && modrinth != null) {
                List<SearchResult> mr = modrinth.search(keyword, "mod");
                if (!mr.isEmpty()) {
                    renderModrinth(keyword, mr, formId, event);
                    return;
                }
            }
            if (page > 1) event.reply("没有更多了。");
            else event.reply("⚠️ 没找到与「" + keyword + "」相关的" + type.label);
            return;
        }
        mrSessions.remove(formId); // 走了 mcmod，清掉可能残留的 modrinth 会话
        sessions.put(formId, p);
        sessionTime.put(formId, System.currentTimeMillis());

        StringBuilder sb = new StringBuilder("## 🔍 「" + keyword + "」· " + type.label
                + "（第 " + page + " 页）\n");
        List<String> labels = new ArrayList<>();
        List<String> datas = new ArrayList<>();
        for (int i = 0; i < p.entries.size() && i < 20; i++) {
            Entry e = p.entries.get(i);
            sb.append(i + 1).append(". ").append(e.title).append('\n');
            labels.add(String.valueOf(i + 1)); // 按钮只显数字，省排版（名字已在正文列出）
            datas.add(DETAIL + i);
        }
        // 翻页按钮
        if (page > 1) { labels.add("⬅上一页"); datas.add(PAGE + (page - 1)); }
        if (p.hasNext) { labels.add("下一页➡"); datas.add(PAGE + (page + 1)); }
        sb.append("\n> 👇 点按钮看详情");
        if (page > 1 || p.hasNext) sb.append("，翻页用上/下一页按钮");
        event.replyKeyboard(sb.toString(), Keyboards.callback(labels, datas));
    }

    /** Modrinth 兜底渲染：MCMOD 没结果时改用 Modrinth 结果（按钮 → /mcd N → Modrinth 详情）。 */
    private void renderModrinth(String keyword, List<SearchResult> results, String formId, BotMessageContext event) {
        sessions.remove(formId);
        mrSessions.put(formId, results);
        sessionTime.put(formId, System.currentTimeMillis());
        StringBuilder sb = new StringBuilder("## 🔍 「" + keyword + "」· 模组（Modrinth）\n");
        sb.append("> ℹ️ MCMOD 没找到，已自动转 Modrinth\n");
        List<String> labels = new ArrayList<>();
        List<String> datas = new ArrayList<>();
        for (int i = 0; i < results.size() && i < 20; i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title).append('\n');
            labels.add(String.valueOf(i + 1)); // 按钮只显数字，省排版
            datas.add(DETAIL + i);
        }
        sb.append("\n> 👇 点按钮看详情");
        event.replyKeyboard(sb.toString(), Keyboards.callback(labels, datas));
    }

    @Override
    public String name() {
        return "mcmod";
    }

    @Override
    public String usage() {
        return "/mod 模组 · /item 物品 · /pack 整合包 · /tutorial 教程";
    }

    @Override
    public String description() {
        return "MCMOD 百科查询（结果可点按钮 + 翻页）";
    }

    @Override
    // category 自动继承模块 displayName

    @Override
    public java.util.List<String> triggers() {
        return java.util.Arrays.asList("/mod", "/item", "/pack", "/tutorial");
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }

    // ==================== 内部 ====================

    private boolean isLive(String formId) {
        Long t = sessionTime.get(formId);
        return t != null && System.currentTimeMillis() - t <= SESSION_TTL;
    }

    private Page liveSession(String formId) {
        if (!isLive(formId)) {
            sessions.remove(formId);
            mrSessions.remove(formId);
            sessionTime.remove(formId);
            return null;
        }
        return sessions.get(formId);
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        sessionTime.entrySet().removeIf(en -> {
            if (now - en.getValue() > SESSION_TTL) {
                sessions.remove(en.getKey());
                mrSessions.remove(en.getKey());
                return true;
            }
            return false;
        });
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
