package org.windy.xingtubot.module;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.service.Translator;
import org.windy.xingtubot.common.util.GroupTargets;
import org.windy.xingtubot.module.github.GithubMessageBuilder;
import org.windy.xingtubot.module.github.GithubSeenStore;
import org.windy.xingtubot.module.github.GithubTrackerService;
import org.windy.xingtubot.module.github.GithubWatchCommand;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/** GitHub project tracking module lifecycle and message delivery. */
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
            ctx.logger().info("[Github] GitHub tracking disabled.");
            return;
        }

        tracker = new GithubTrackerService(ctx.logger(), ctx.dataFolder());
        tracker.setPollIntervalSeconds(config.getInt("github-poll-interval-seconds", 300));
        tracker.setMirrors(config.getStringList("github-mirrors"));

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
                ctx.logger().info("[Github] seen state storage: " + storageType);
            } catch (Exception e) {
                ctx.logger().warn("[Github] DB state init failed, fallback to YAML: " + e.getMessage());
            }
        }

        List<String> configuredGroups = config.getStringList("allowed-groups");

        ProactiveSender sender = ctx.getService(ProactiveSender.class);
        Translator translator = ctx.getService(Translator.class);
        GithubMessageBuilder builder = new GithubMessageBuilder(translator);

        tracker.setChangeListener(new GithubTrackerService.ChangeListener() {
            @Override
            public void onNewRelease(String owner, String repo, String tagName, String name, String url) {
                push(builder.buildRelease(owner, repo, tagName, name, url), configuredGroups, sender, ctx);
            }

            @Override
            public void onNewCommit(String owner, String repo, String sha, String message, String author, String url) {
                push(builder.buildCommit(owner, repo, sha, message, author, url), configuredGroups, sender, ctx);
            }

            @Override
            public void onNewIssue(String owner, String repo, int number, String title, String action,
                                   String author, String labels, String body, String url) {
                push(builder.buildIssue(owner, repo, number, title, action, author, labels, body, url),
                        configuredGroups, sender, ctx);
            }

            @Override
            public void onNewPr(String owner, String repo, int number, String title, String action,
                                String author, String body, String url) {
                push(builder.buildPr(owner, repo, number, title, action, author, body, url),
                        configuredGroups, sender, ctx);
            }
        });

        tracker.start();
        ctx.registry().register(new GithubWatchCommand(tracker));

        ctx.logger().info("[Github] tracking loaded.");
    }

    private void push(String md, List<String> configuredGroups, ProactiveSender sender, ModuleContext ctx) {
        List<String> groups = GroupTargets.resolveKnownGroups(ctx, configuredGroups);
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
        }

        echoToGame(ctx, md);
    }

    @SuppressWarnings("unchecked")
    private void echoToGame(ModuleContext ctx, String md) {
        Object echo = ctx.getServiceObject("xingtubot.chatlink.gameEcho");
        if (echo instanceof Consumer) {
            ((Consumer<String>) echo).accept(md);
        }
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            tracker.stop();
            tracker = null;
        }
    }
}
