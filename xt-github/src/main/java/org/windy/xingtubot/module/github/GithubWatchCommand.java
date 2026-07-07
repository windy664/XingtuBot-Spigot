package org.windy.xingtubot.module.github;

import org.windy.xingtubot.common.command.GroupCommand;
import org.windy.xingtubot.common.event.BotMessageEvent;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /github 命令：管理 GitHub 仓库追踪。
 */
public class GithubWatchCommand implements GroupCommand {

    private static final Pattern REPO_PATTERN = Pattern.compile("([\\w.-]+)/([\\w.-]+)");
    private final GithubTrackerService tracker;

    public GithubWatchCommand(GithubTrackerService tracker) {
        this.tracker = tracker;
    }

    @Override
    public boolean matches(String message) {
        return message != null && message.toLowerCase().startsWith("/github");
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String[] parts = message.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        if (args.length == 0) {
            event.reply("用法: /github <watch|unwatch|list> [owner/repo]\n"
                    + "GitHub: /github watch owner/repo\n"
                    + "Gitee:  /github watch gitee:owner/repo （或贴 gitee.com 链接）");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "watch":
            case "add":
                handleWatch(args, event);
                break;
            case "unwatch":
            case "remove":
                handleUnwatch(args, event);
                break;
            case "list":
            case "ls":
                handleList(event);
                break;
            default:
                event.reply("未知子命令: " + args[0] + "\n用法: /github <watch|unwatch|list> [owner/repo]");
        }
    }

    private void handleWatch(String[] args, BotMessageEvent event) {
        if (args.length < 2) {
            event.reply("用法: /github watch <owner/repo>\n"
                    + "GitHub 示例: /github watch windy664/XingtuBot-Spigot\n"
                    + "Gitee 示例:  /github watch gitee:owner/repo");
            return;
        }
        boolean gitee = isGitee(args[1]);
        String repoStr = normalizeRepo(args[1]);
        Matcher m = REPO_PATTERN.matcher(repoStr);
        if (!m.matches()) {
            event.reply("格式错误，应为 owner/repo（如 windy664/XingtuBot-Spigot）");
            return;
        }
        String label = (gitee ? "Gitee " : "GitHub ") + repoStr;
        boolean added = tracker.watch(m.group(1), m.group(2), gitee);
        event.reply(added ? "✅ 已订阅 " + label : "⚠️ 已经在订阅 " + label + " 了");
    }

    private void handleUnwatch(String[] args, BotMessageEvent event) {
        if (args.length < 2) {
            event.reply("用法: /github unwatch <owner/repo>");
            return;
        }
        boolean gitee = isGitee(args[1]);
        String repoStr = normalizeRepo(args[1]);
        Matcher m = REPO_PATTERN.matcher(repoStr);
        if (!m.matches()) {
            event.reply("格式错误，应为 owner/repo");
            return;
        }
        String label = (gitee ? "Gitee " : "GitHub ") + repoStr;
        boolean removed = tracker.unwatch(m.group(1), m.group(2), gitee);
        event.reply(removed ? "✅ 已取消订阅 " + label : "⚠️ 未找到订阅 " + label);
    }

    /** 是否为 Gitee 目标（gitee: 前缀或 gitee.com 链接）。 */
    private boolean isGitee(String input) {
        String s = input.toLowerCase();
        return s.startsWith("gitee:") || s.contains("gitee.com/");
    }

    private void handleList(BotMessageEvent event) {
        List<GithubTrackerService.WatchedRepo> list = tracker.listWatched();
        if (list.isEmpty()) {
            event.reply("当前无订阅。使用 /github watch <owner/repo> 添加。");
            return;
        }
        StringBuilder sb = new StringBuilder("📋 项目追踪订阅列表：\n");
        for (GithubTrackerService.WatchedRepo wr : list) {
            sb.append("· ").append(wr.gitee ? "[Gitee] " : "[GitHub] ");
            sb.append(wr.owner).append("/").append(wr.repo);
            sb.append(" [");
            if (wr.watchReleases) sb.append("release ");
            if (wr.watchCommits) sb.append("commit ");
            if (wr.watchIssues) sb.append("issue ");
            if (wr.watchPrs) sb.append("PR ");
            sb.append("]\n");
        }
        event.reply(sb.toString().trim());
    }

    private String normalizeRepo(String input) {
        // 去掉 gitee: 前缀
        if (input.toLowerCase().startsWith("gitee:")) input = input.substring("gitee:".length());
        // 去掉各平台域名前缀
        for (String prefix : new String[]{
                "https://github.com/", "http://github.com/",
                "https://gitee.com/", "http://gitee.com/"}) {
            if (input.toLowerCase().startsWith(prefix)) {
                input = input.substring(prefix.length());
                break;
            }
        }
        if (input.endsWith("/")) input = input.substring(0, input.length() - 1);
        return input;
    }

    @Override
    public String name() { return "github"; }

    /** 项目追踪是服务器管理功能，整组仅超管可用。 */
    @Override
    public boolean adminOnly() { return true; }

    @Override
    public List<String> triggers() { return Arrays.asList("/github", "github"); }
    @Override
    public String usage() { return "/github <watch|unwatch|list>"; }
    @Override
    public String description() { return "GitHub / Gitee 项目追踪（release/commit/issue/PR）"; }
    @Override
    public String category() { return "🔍 模组工具"; }}
