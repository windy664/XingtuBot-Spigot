package org.windy.xingtubot.module;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.capability.GameEcho;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.service.Translator;
import org.windy.xingtubot.common.util.Md;
import org.windy.xingtubot.module.github.GithubTrackerService;
import org.windy.xingtubot.module.github.GithubWatchCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 项目追踪模块：追踪 GitHub/Gitee repo 的 release / commit / issue / PR 变化，推送到 QQ 群。
 * 优化：深度整合 BaiduTranslateService，并使用富文本重新排版了 Markdown 消息，做到美观、详尽且中英对照。
 */
public final class GithubModule implements BotModule {

    private GithubTrackerService tracker;

    @Override
    public String name() {
        return "github";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        BotConfig config = ctx.config();

        if (!config.getBoolean("github-watch-enable", true)) {
            ctx.logger().info("[Github] GitHub 项目追踪已禁用。");
            return;
        }

        tracker = new GithubTrackerService(ctx.logger(), ctx.dataFolder());
        tracker.setPollIntervalSeconds(config.getInt("github-poll-interval-seconds", 300));
        tracker.setMirrors(config.getStringList("github-mirrors"));

        List<String> allowedGroups = config.getStringList("allowed-groups");
        List<String> targetGroups = new ArrayList<>();
        boolean broadcastAll = allowedGroups.isEmpty();
        for (String g : allowedGroups) {
            if (g == null || g.trim().isEmpty()) continue;
            if ("*".equals(g.trim())) { broadcastAll = true; continue; }
            targetGroups.add(g.trim());
        }
        final boolean toAll = broadcastAll;

        ProactiveSender sender = ctx.getService(ProactiveSender.class);
        Translator translator = ctx.getService(Translator.class);

        tracker.setChangeListener(new GithubTrackerService.ChangeListener() {

            // 文本预处理：截断过长的文本，防止 Markdown 超长报错
            private String truncate(String text, int max) {
                if (text == null) return "";
                if (text.length() <= max) return text;
                return text.substring(0, max) + "...";
            }

            @Override
            public void onNewRelease(String owner, String repo, String tagName, String name, String url) {
                String rawName = name == null || name.isEmpty() ? tagName : name;
                String translated = "";
                if (translator != null && translator.isEnabled()) {
                    translated = translator.translateEnToZh(rawName);
                }

                Md card = Md.card("📦", "新版本发布")
                        .subtitle("**" + owner + "/" + repo + "** ｜ `" + tagName + "`");

                if (!translated.equals(rawName) && !translated.isEmpty()) {
                    card.quote("🌐 " + translated + "\n📝 原文: " + truncate(rawName, 100));
                } else {
                    card.quote(truncate(rawName, 150));
                }

                String md = card.link("🔗 点击前往仓库查看下载", url).build();
                push(md, targetGroups, toAll, sender, ctx);
            }

            @Override
            public void onNewCommit(String owner, String repo, String sha, String message, String author, String url) {
                // Commit信息通常比较长，拆分标题和正文
                String[] parts = message.split("\n", 2);
                String msgTitle = parts[0];
                String msgBody = parts.length > 1 ? parts[1].trim() : "";

                String translatedTitle = "";
                if (translator != null && translator.isEnabled()) {
                    translatedTitle = translator.translateEnToZh(msgTitle);
                }

                Md card = Md.card("🔨", "代码提交记录")
                        .subtitle("**" + owner + "/" + repo + "**");

                if (!translatedTitle.equals(msgTitle) && !translatedTitle.isEmpty()) {
                    card.quote("🌐 **" + translatedTitle + "**\n📝 " + truncate(msgTitle, 100));
                } else {
                    card.quote("📝 " + truncate(msgTitle, 150));
                }

                card.field("👤", "提交者", author)
                        .field("🔑", "Hash", "`" + sha + "`");

                String md = card.link("🔗 查看此次代码变更", url).build();
                push(md, targetGroups, toAll, sender, ctx);
            }

            @Override
            public void onNewIssue(String owner, String repo, int number, String title, String action, String url) {
                String emoji = "open".equalsIgnoreCase(action) ? "🟢" : "🔴";
                String actionZh = "open".equalsIgnoreCase(action) ? "开启" : ("closed".equalsIgnoreCase(action) ? "关闭" : action);

                String translated = "";
                if (translator != null && translator.isEnabled()) {
                    translated = translator.translateEnToZh(title);
                }

                Md card = Md.card(emoji, "Issue #" + number + " [" + actionZh + "]")
                        .subtitle("**" + owner + "/" + repo + "**");

                if (!translated.equals(title) && !translated.isEmpty()) {
                    card.quote("🌐 " + translated + "\n📝 原文: " + truncate(title, 100));
                } else {
                    card.quote("📝 " + truncate(title, 150));
                }

                String md = card.link("🔗 参与讨论", url).build();
                push(md, targetGroups, toAll, sender, ctx);
            }

            @Override
            public void onNewPr(String owner, String repo, int number, String title, String action, String url) {
                String emoji = "open".equalsIgnoreCase(action) ? "🟡" : "🟣";
                String actionZh = "open".equalsIgnoreCase(action) ? "发起" : ("closed".equalsIgnoreCase(action) ? "关闭/合并" : action);

                String translated = "";
                if (translator != null && translator.isEnabled()) {
                    translated = translator.translateEnToZh(title);
                }

                Md card = Md.card(emoji, "Pull Request #" + number + " [" + actionZh + "]")
                        .subtitle("**" + owner + "/" + repo + "**");

                if (!translated.equals(title) && !translated.isEmpty()) {
                    card.quote("🌐 " + translated + "\n📝 原文: " + truncate(title, 100));
                } else {
                    card.quote("📝 " + truncate(title, 150));
                }

                String md = card.link("🔗 审查代码请求", url).build();
                push(md, targetGroups, toAll, sender, ctx);
            }
        });

        tracker.start();
        ctx.registry().register(new GithubWatchCommand(tracker));

        ctx.logger().info("[Github] GitHub 项目追踪已加载（"
                + (toAll ? "推送到全部已知群" : targetGroups.size() + " 个目标群") + "）");
    }

    private void push(String md, List<String> targetGroups, boolean toAll,
                      ProactiveSender sender, ModuleContext ctx) {
        List<String> groups = toAll
                ? new ArrayList<>(org.windy.xingtubot.common.queue.KnownGroupStore.getInstance().all())
                : targetGroups;

        if (sender != null && sender.isReady() && !groups.isEmpty()) {
            for (String gid : groups) {
                if (!sender.sendGroupMarkdown(gid, md)) {
                    PendingMessageQueue.getInstance().offer(gid, md);
                }
            }
        } else if (!groups.isEmpty()) {
            for (String gid : groups) {
                PendingMessageQueue.getInstance().offer(gid, md);
            }
        } else {
            PendingMessageQueue.getInstance().offer(md);
        }
        GameEcho echo = ctx.getService(GameEcho.class);
        if (echo != null) echo.echo(md);
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            tracker.stop();
            tracker = null;
        }
    }
}