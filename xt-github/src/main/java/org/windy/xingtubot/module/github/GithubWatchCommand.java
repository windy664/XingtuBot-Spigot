package org.windy.xingtubot.module.github;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.util.Md;

import java.util.Arrays;
import java.util.List;

/**
 * /github 命令：管理 GitHub 仓库追踪。
 */
public class GithubWatchCommand implements BotCommand {

    private final GithubTrackerService tracker;

    public GithubWatchCommand(GithubTrackerService tracker) {
        this.tracker = tracker;
    }

    @Override
    public boolean matches(String message) {
        return message != null && message.toLowerCase().startsWith("/github");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String[] parts = message.split("\\s+");
        String[] args = parts.length > 1 ? Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        if (args.length == 0) {
            event.replyMarkdown(Md.card("🔍", "GitHub 追踪")
                    .field("📡", "订阅", "`/github watch owner/repo`")
                    .field("📡", "带分支", "`/github watch owner/repo/branch`")
                    .field("📡", "Gitee", "`/github watch gitee:owner/repo`")
                    .field("🗑", "取消", "`/github unwatch owner/repo`")
                    .field("📋", "列表", "`/github list`")
                    .build(), null);
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
                event.replyMarkdown(Md.card("❓", "未知子命令")
                        .quote("可用: watch / unwatch / list")
                        .build(), null);
        }
    }

    private String[] parseRepoPath(String input, boolean gitee) {
        String s = normalizeRepo(input);
        String[] segs = s.split("/", -1);
        if (segs.length < 2) return null;
        String owner = segs[0];
        String repo = segs[1];
        String branch = null;
        if (segs.length > 2) {
            StringBuilder sb = new StringBuilder(segs[2]);
            for (int i = 3; i < segs.length; i++) sb.append("/").append(segs[i]);
            String b = sb.toString();
            if (!b.isEmpty()) branch = b;
        }
        return new String[]{owner, repo, branch};
    }

    private void handleWatch(String[] args, BotMessageContext event) {
        if (args.length < 2) {
            event.replyMarkdown(Md.card("📡", "订阅项目")
                    .field("", "GitHub", "`/github watch owner/repo`")
                    .field("", "带分支", "`/github watch owner/repo/branch`")
                    .field("", "Gitee", "`/github watch gitee:owner/repo`")
                    .build(), null);
            return;
        }
        boolean gitee = isGitee(args[1]);
        String[] parsed = parseRepoPath(args[1], gitee);
        if (parsed == null) {
            event.replyMarkdown(Md.card("❌", "格式错误")
                    .quote("应为 `owner/repo[/branch]`")
                    .build(), null);
            return;
        }
        String owner = parsed[0], repo = parsed[1], branch = parsed[2];
        String platform = gitee ? "Gitee" : "GitHub";
        String label = owner + "/" + repo;
        if (branch != null) label += " `" + branch + "`";

        String result = tracker.watch(owner, repo, gitee, branch);
        if (result == null) {
            event.replyMarkdown(Md.card("✅", "订阅成功")
                    .subtitle("**" + platform + "** ｜ " + label)
                    .field("📡", "追踪", "release / commit / issue / PR")
                    .build(), null);
        } else if ("already".equals(result)) {
            event.replyMarkdown(Md.card("⚠️", "重复订阅")
                    .quote(label + " 已在订阅列表中")
                    .build(), null);
        } else {
            event.replyMarkdown(Md.card("❌", "订阅失败")
                    .quote(result)
                    .build(), null);
        }
    }

    private void handleUnwatch(String[] args, BotMessageContext event) {
        if (args.length < 2) {
            event.replyMarkdown(Md.card("🗑", "取消订阅")
                    .quote("用法: `/github unwatch owner/repo[/branch]`")
                    .build(), null);
            return;
        }
        boolean gitee = isGitee(args[1]);
        String[] parsed = parseRepoPath(args[1], gitee);
        if (parsed == null) {
            event.replyMarkdown(Md.card("❌", "格式错误")
                    .quote("应为 `owner/repo[/branch]`")
                    .build(), null);
            return;
        }
        String owner = parsed[0], repo = parsed[1], branch = parsed[2];
        String platform = gitee ? "Gitee" : "GitHub";
        String label = owner + "/" + repo;
        if (branch != null) label += " `" + branch + "`";

        boolean removed = tracker.unwatch(owner, repo, gitee, branch);
        if (removed) {
            event.replyMarkdown(Md.card("✅", "已取消订阅")
                    .subtitle("**" + platform + "** ｜ " + label)
                    .build(), null);
        } else {
            event.replyMarkdown(Md.card("⚠️", "未找到")
                    .quote(label + " 不在订阅列表中")
                    .build(), null);
        }
    }

    private boolean isGitee(String input) {
        String s = input.toLowerCase();
        return s.startsWith("gitee:") || s.contains("gitee.com/");
    }

    private void handleList(BotMessageContext event) {
        List<GithubTrackerService.WatchedRepo> list = tracker.listWatched();
        if (list.isEmpty()) {
            event.replyMarkdown(Md.card("📋", "项目追踪")
                    .quote("暂无订阅。使用 `/github watch owner/repo` 添加")
                    .build(), null);
            return;
        }
        Md md = Md.card("📋", "项目追踪（" + list.size() + "）");
        for (GithubTrackerService.WatchedRepo wr : list) {
            String platform = wr.gitee ? "Gitee" : "GitHub";
            String name = wr.owner + "/" + wr.repo;
            if (wr.branch != null) name += " `" + wr.branch + "`";

            StringBuilder tags = new StringBuilder();
            if (wr.watchReleases) tags.append("release ");
            if (wr.watchCommits) tags.append("commit ");
            if (wr.watchIssues) tags.append("issue ");
            if (wr.watchPrs) tags.append("PR ");

            md.field("📡", name, platform + " ｜ " + tags.toString().trim());
        }
        event.replyMarkdown(md.build(), null);
    }

    private String normalizeRepo(String input) {
        if (input.toLowerCase().startsWith("gitee:")) input = input.substring("gitee:".length());
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

    @Override
    public boolean adminOnly() { return true; }

    @Override
    public List<String> triggers() { return Arrays.asList("/github", "github"); }
    @Override
    public String usage() { return "/github <watch|unwatch|list> [owner/repo/branch]"; }
    @Override
    public String description() { return "GitHub / Gitee 项目追踪（release/commit/issue/PR），支持指定分支"; }
    @Override
    // category 自动继承模块 displayName
}
