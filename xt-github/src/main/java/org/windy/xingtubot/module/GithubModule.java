package org.windy.xingtubot.module;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.capability.GameEcho;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.service.Translator;
import org.windy.xingtubot.module.github.GithubMessageBuilder;
import org.windy.xingtubot.module.github.GithubSeenStore;
import org.windy.xingtubot.module.github.GithubTrackerService;
import org.windy.xingtubot.module.github.GithubWatchCommand;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 项目追踪模块：负责核心生命周期调度和消息分发。
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

        // DB 持久化（可选，默认 yaml 文件）
        String storageType = config.getString("github-storage-type", "yaml").trim().toLowerCase();
        if ("sqlite".equals(storageType) || "mysql".equals(storageType)) {
            try {
                GithubSeenStore store;
                if ("sqlite".equals(storageType)) {
                    File db = new File(ctx.dataFolder(), "github-seen.db");
                    store = GithubSeenStore.sqlite(db.getAbsolutePath(), ctx.logger()::warn);
                } else {
                    store = GithubSeenStore.mysql(
                            config.getString("mysql-host", "127.0.0.1"),
                            config.getInt("mysql-port", 3306),
                            config.getString("mysql-database", "xingtubot"),
                            config.getString("mysql-user", "root"),
                            config.getString("mysql-password", ""),
                            ctx.logger()::warn);
                }
                tracker.setSeenStore(store);
                ctx.logger().info("[Github] seen state 持久化: " + storageType);
            } catch (Exception e) {
                ctx.logger().warn("[Github] 初始化 DB 持久化失败，回退 YAML: " + e.getMessage());
            }
        }

        List<String> targetGroups = parseTargetGroups(config.getStringList("allowed-groups"));
        boolean toAll = config.getStringList("allowed-groups").isEmpty() || config.getStringList("allowed-groups").contains("*");

        ProactiveSender sender = ctx.getService(ProactiveSender.class);
        Translator translator = ctx.getService(Translator.class);
        GithubMessageBuilder builder = new GithubMessageBuilder(translator);

        tracker.setChangeListener(new GithubTrackerService.ChangeListener() {
            @Override
            public void onNewRelease(String owner, String repo, String tagName, String name, String url) {
                push(builder.buildRelease(owner, repo, tagName, name, url), targetGroups, toAll, sender, ctx);
            }

            @Override
            public void onNewCommit(String owner, String repo, String sha, String message, String author, String url) {
                push(builder.buildCommit(owner, repo, sha, message, author, url), targetGroups, toAll, sender, ctx);
            }

            @Override
            public void onNewIssue(String owner, String repo, int number, String title, String action,
                                   String author, String labels, String body, String url) {
                push(builder.buildIssue(owner, repo, number, title, action, author, labels, body, url),
                        targetGroups, toAll, sender, ctx);
            }

            @Override
            public void onNewPr(String owner, String repo, int number, String title, String action,
                                String author, String body, String url) {
                push(builder.buildPr(owner, repo, number, title, action, author, body, url),
                        targetGroups, toAll, sender, ctx);
            }
        });

        tracker.start();
        ctx.registry().register(new GithubWatchCommand(tracker));

        ctx.logger().info("[Github] 项目追踪已加载（推送到 " + (toAll ? "全部已知群" : targetGroups.size() + " 个群") + "）");
    }

    private List<String> parseTargetGroups(List<String> allowed) {
        List<String> targets = new ArrayList<>();
        for (String g : allowed) {
            if (g != null && !g.trim().isEmpty() && !g.trim().equals("*")) {
                targets.add(g.trim());
            }
        }
        return targets;
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
