package org.windy.xingtubot.common.command.impl;

import org.windy.xingtubot.common.command.GroupCommand;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.service.ModrinthApiService;
import org.windy.xingtubot.common.service.ModrinthApiService.ProjectDetail;
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
 * Modrinth 搜索命令：/mod 搜模组，/pack 搜整合包。
 * 交互式 session（搜索 → 回复序号 → 详情），60秒超时。
 */
public class ModrinthCommand implements GroupCommand {

    private static final long SESSION_TTL = 300_000; // 5 分钟：结果按钮可能晚点才被点
    /** 点搜索结果按钮回传的详情指令前缀（按钮 data = DETAIL_CMD + 序号）。 */
    private static final String DETAIL_CMD = "/mrdetail ";
    private static final long CLEAN_INTERVAL = 300_000;

    private final ModrinthApiService api;
    private volatile boolean markdownEnabled = false;

    /** formId -> 搜索会话 */
    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ModrinthSessionCleaner");
        t.setDaemon(true);
        return t;
    });

    public ModrinthCommand(ModrinthApiService api) {
        this.api = api;
        cleaner.scheduleAtFixedRate(this::cleanExpiredSessions, 0L, CLEAN_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void setMarkdownEnabled(boolean enabled) {
        this.markdownEnabled = enabled;
    }

    @Override
    public boolean matches(String message) {
        String msg = message.trim().toLowerCase();
        // /mr = 显式走 Modrinth（mcmod 开时它抢了 /mod，留 /mr 作 Modrinth 备用入口）
        // 搜索 + 点结果按钮回传的 /mrdetail N（去掉裸数字匹配，消除与其它命令序号冲突）
        return msg.startsWith("/mod ") || msg.startsWith("/mr ") || msg.startsWith("/pack ")
                || msg.equals("/modtest") || msg.startsWith("/mrdetail ");
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String formId = event.getFormId();
        String msg = message.trim();

        // 诊断命令
        if (msg.equalsIgnoreCase("/modtest")) {
            event.reply("正在诊断搜索 API，请稍候...");
            event.reply(api.testConnection());
            return;
        }

        // Step ① 搜索（/mod 或 /mr 都搜模组）
        if (msg.toLowerCase().startsWith("/mod ") || msg.toLowerCase().startsWith("/mr ")) {
            String keyword = msg.substring(msg.indexOf(' ') + 1).trim();
            if (keyword.isEmpty()) {
                event.reply("用法: /mr <模组名>\n示例: /mr create");
                return;
            }
            doSearch(keyword, "mod", formId, event);
            return;
        }

        if (msg.toLowerCase().startsWith("/pack ")) {
            String keyword = msg.substring(6).trim();
            if (keyword.isEmpty()) {
                event.reply("用法: /pack <整合包名>\n示例: /pack ftb");
                return;
            }
            doSearch(keyword, "modpack", formId, event);
            return;
        }

        // Step ② 点结果按钮看详情（msg = /mrdetail <下标>）
        if (msg.startsWith(DETAIL_CMD)) {
            SearchSession session = sessions.get(formId);
            if (session == null || System.currentTimeMillis() - session.createTime > SESSION_TTL) {
                sessions.remove(formId);
                event.reply("⚠️ 搜索结果已过期，请重新搜索。");
                return;
            }

            int index;
            try {
                index = Integer.parseInt(msg.substring(DETAIL_CMD.length()).trim());
            } catch (NumberFormatException ex) {
                return;
            }
            if (index < 0 || index >= session.results.size()) {
                event.reply("❌ 无效的结果项，请重新搜索。");
                return;
            }

            SearchResult target = session.results.get(index);
            event.reply("正在查询详情，请稍候...");

            ProjectDetail detail = api.getDetail(target.slug);
            if (detail == null) {
                event.reply("❌ 获取详情失败，请稍后再试。");
                return;
            }

            // 分段发送详情（正文超长自动分多条，画廊全量；被动回复每条限 5 次由平台侧处理）
            api.sendDetail(event, detail, markdownEnabled);
        }
    }

    @Override
    public String name() {
        return "modrinth";
    }
    @Override
    public String usage() { return "/mr <模组名> · /pack <整合包名>"; }
    @Override
    public String description() { return "Modrinth 搜索（英文源，自动翻译）"; }
    @Override
    public String category() { return "🔍 模组工具"; }
    public void shutdown() {
        cleaner.shutdownNow();
    }

    // ==================== 内部 ====================

    private void doSearch(String keyword, String projectType, String formId, BotMessageEvent event) {
        List<SearchResult> results = api.search(keyword, projectType);
        if (results.isEmpty()) {
            String typeLabel = "modpack".equals(projectType) ? "整合包" : "模组";
            event.reply("⚠️ 没找到与「" + keyword + "」相关的" + typeLabel + "。");
            return;
        }

        sessions.put(formId, new SearchSession(keyword, results, projectType));
        String body = markdownEnabled
                ? api.formatSearchResultsMarkdown(results, keyword)
                : api.formatSearchResults(results, keyword);
        // 结果带「可点按钮」：点按钮看详情，不用回复序号
        List<String> labels = new ArrayList<>();
        List<String> datas = new ArrayList<>();
        for (int i = 0; i < results.size() && i < 25; i++) {
            labels.add(String.valueOf(i + 1)); // 按钮只显数字，省排版（名字已在正文列出）
            datas.add(DETAIL_CMD + i);
        }
        event.replyKeyboard(body + "\n> 👇 点下方按钮查看详情", Keyboards.callback(labels, datas));
    }

    /** 截断按钮文字。 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static class SearchSession {
        final String keyword;
        final List<SearchResult> results;
        final String projectType;
        final long createTime = System.currentTimeMillis();

        SearchSession(String keyword, List<SearchResult> results, String projectType) {
            this.keyword = keyword;
            this.results = results;
            this.projectType = projectType;
        }
    }

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(en -> now - en.getValue().createTime > SESSION_TTL);
    }

}
