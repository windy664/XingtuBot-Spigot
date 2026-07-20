package org.windy.xingtubot.module.github;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;

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
            event.reply("用法: /github <watch|unwatch|list> [owner/repo/branch]\n"
                    + "GitHub: /github watch windy664/XingtuBot-Spigot\n"
                    + "带分支: /github watch windy664/XingtuBot-Spigot/26.2\n"
                    + "Gitee:  /github watch gitee:owner/repo/dev");
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
                event.reply("未知子命令: " + args[0] + "\n用法: /github <watch|unwatch|list> [owner/repo/branch]");
        }
    }

    /**
     * 解析 owner/repo[/branch...] 路径。
     * 返回 String[3]: {owner, repo, branchOrNull}
     */
    private String[] parseRepoPath(String input, boolean gitee) {
        String s = normalizeRepo(input);
        String[] segs = s.split("/", -1);
        if (segs.length < 2) return null;
        String owner = segs[0];
        String repo = segs[1];
        String branch = null;
        if (segs.length > 2) {
            // 分支名可能含 /，把第三段及之后拼回来
            StringBuilder sb = new StringBuilder(segs[2]);
            for (int i = 3; i < segs.length; i++) sb.append("/").append(segs[i]);
            String b = sb.toString();
            if (!b.isEmpty()) branch = b;
        }
        return new String[]{owner, repo, branch};
    }

    private void handleWatch(String[] args, BotMessageContext event) {
        if (args.length < 2) {
            event.reply("用法: /github watch <owner/repo[/branch]>\n"
                    + "GitHub 示例: /github watch windy664/XingtuBot-Spigot\n"
                    + "带分支:     /github watch windy664/XingtuBot-Spigot/26.2\n"
                    + "Gitee 示例:  /github watch gitee:owner/repo/dev");
            return;
        }
        boolean gitee = isGitee(args[1]);
        String[] parsed = parseRepoPath(args[1], gitee);
        if (parsed == null) {
            event.reply("格式错误，应为 owner/repo[/branch]（如 windy664/XingtuBot-Spigot/26.2）");
            return;
        }
        String owner = parsed[0], repo = parsed[1], branch = parsed[2];
        String label = (gitee ? "Gitee " : "GitHub ") + owner + "/" + repo;
        if (branch != null) label += " [" + branch + "]";
        boolean added = tracker.watch(owner, repo, gitee, branch);
        event.reply(added ? "✅ 已订阅 " + label : "⚠️ 已经在订阅 " + label + " 了");
    }

    private void handleUnwatch(String[] args, BotMessageContext event) {
        if (args.length < 2) {
            event.reply("用法: /github unwatch <owner/repo[/branch]>");
            return;
        }
        boolean gitee = isGitee(args[1]);
        String[] parsed = parseRepoPath(args[1], gitee);
        if (parsed == null) {
            event.reply("格式错误，应为 owner/repo[/branch]");
            return;
        }
        String owner = parsed[0], repo = parsed[1], branch = parsed[2];
        String label = (gitee ? "Gitee " : "GitHub ") + owner + "/" + repo;
        if (branch != null) label += " [" + branch + "]";
        boolean removed = tracker.unwatch(owner, repo, gitee, branch);
        event.reply(removed ? "✅ 已取消订阅 " + label : "⚠️ 未找到订阅 " + label);
    }

    private boolean isGitee(String input) {
        String s = input.toLowerCase();
        return s.startsWith("gitee:") || s.contains("gitee.com/");
    }

    private void handleList(BotMessageContext event) {
        List<GithubTrackerService.WatchedRepo> list = tracker.listWatched();
        if (list.isEmpty()) {
            event.reply("当前无订阅。使用 /github watch <owner/repo> 添加。");
            return;
        }
        StringBuilder sb = new StringBuilder("📋 项目追踪订阅列表：\n");
        for (GithubTrackerService.WatchedRepo wr : list) {
            sb.append("· ").append(wr.gitee ? "[Gitee] " : "[GitHub] ");
            sb.append(wr.owner).append("/").append(wr.repo);
            if (wr.branch != null) sb.append("/").append(wr.branch);
            sb.append(" (");
            if (wr.watchReleases) sb.append("release ");
            if (wr.watchCommits) sb.append("commit ");
            if (wr.watchIssues) sb.append("issue ");
            if (wr.watchPrs) sb.append("PR ");
            sb.append(")\n");
        }
        event.reply(sb.toString().trim());
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
    public String category() { return "🔍 模组工具"; }
}
