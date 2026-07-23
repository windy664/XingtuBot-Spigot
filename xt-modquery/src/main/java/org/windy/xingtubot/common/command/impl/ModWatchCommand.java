package org.windy.xingtubot.common.command.impl;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.service.ModUpdateService;
import org.windy.xingtubot.common.service.WatchEntry;

import java.util.List;

/**
 * 群聊命令：模组更新监控管理。
 * 前缀 /modwatch，支持 list / add / remove / check / help。
 */
public class ModWatchCommand implements BotCommand {

    private final ModUpdateService service;

    public ModWatchCommand(ModUpdateService service) {
        this.service = service;
    }

    @Override
    public boolean matches(String message) {
        return message.toLowerCase().startsWith("/modwatch");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String[] parts = message.trim().split("\\s+", 3);
        String sub = parts.length > 1 ? parts[1].toLowerCase() : "help";

        switch (sub) {
            case "list":
                handleList(event);
                break;
            case "add":
                handleAdd(parts, event);
                break;
            case "remove":
            case "rm":
            case "del":
                handleRemove(parts, event);
                break;
            case "check":
                handleCheck(parts, event);
                break;
            case "feed":
                handleFeed(parts, event);
                break;
            case "github":
            case "ghfeed":
            case "gitee":
            case "subscribe":
            case "sub":
            case "unsubscribe":
            case "unsub":
                // GitHub / Gitee 追踪已迁移到独立的「项目追踪」功能
                event.reply("ℹ️ GitHub / Gitee 仓库追踪已迁移到 /github 命令：\n"
                        + "  /github watch owner/repo          （GitHub）\n"
                        + "  /github watch gitee:owner/repo    （Gitee）\n"
                        + "  /github list / unwatch\n"
                        + "本命令（/modwatch）只负责 Modrinth 模组版本与新模组发现。");
                break;
            case "help":
            default:
                handleHelp(event);
                break;
        }
    }

    @Override
    public String name() {
        return "modwatch";
    }
    @Override
    public String usage() { return "/modwatch <list|add|remove|check>"; }
    @Override
    public String description() { return "Modrinth 模组更新监控"; }
    // category 自动继承模块 displayName

    /** 整体不强制超管（list/check/feed 等只读人人可用），但增删监控项按 {@link #adminFor} 限超管。 */
    @Override
    public boolean adminOnly() {
        return false;
    }

    /** 改动监控配置（增删订阅）= 管理操作，仅超管；只读子命令人人可用。 */
    @Override
    public boolean adminFor(String message) {
        String[] parts = message.trim().split("\\s+", 3);
        String sub = parts.length > 1 ? parts[1].toLowerCase() : "help";
        switch (sub) {
            case "add":
            case "remove":
            case "rm":
            case "del":
            case "subscribe":
            case "sub":
            case "unsubscribe":
            case "unsub":
                return true;   // 增删监控/订阅 → 仅超管
            default:
                return false;  // list / check / feed / github / help → 人人可用
        }
    }

    // ==================== 子命令实现 ====================

    private void handleList(BotMessageContext event) {
        List<WatchEntry> watches = service.listWatches();
        if (watches.isEmpty()) {
            event.reply("📋 Modrinth 监控列表为空\n使用 /modwatch add <slug> 添加");
            return;
        }
        org.windy.xingtubot.common.util.Md card = org.windy.xingtubot.common.util.Md
                .card("📋", "Modrinth 监控列表（" + watches.size() + " 个）");
        for (int i = 0; i < watches.size(); i++) {
            WatchEntry w = watches.get(i);
            String name = w.getDisplayName() != null ? w.getDisplayName() : w.getSlug();
            card.line((i + 1) + ". **" + name + "**　`" + w.getMcVersion() + "/" + w.getLoader() + "`"
                    + (w.getLastCheckTime() > 0 ? "　✓" : ""));
        }
        card.quote("使用 /modwatch check 立即检查");
        event.replyMarkdown(card.build(), null);
    }

    private void handleAdd(String[] parts, BotMessageContext event) {
        if (parts.length < 3) {
            event.reply("用法:\n"
                    + "  /modwatch add <slug> [mc版本] [加载器]\n"
                    + "示例:\n"
                    + "  /modwatch add create 1.20.1 forge\n"
                    + "  /modwatch add sodium 1.20.1 fabric\n"
                    + "（GitHub 仓库追踪请在 config.yml 的 modwatch-github-repos 中配置）");
            return;
        }
        String arg = parts[2].trim();

        if (arg.toLowerCase().startsWith("gh:") || arg.toLowerCase().startsWith("gitee:")
                || arg.toLowerCase().contains("github.com/") || arg.toLowerCase().contains("gitee.com/")) {
            event.reply("⚠️ GitHub / Gitee 仓库追踪请使用 /github 命令：\n"
                    + "  /github watch owner/repo          （GitHub）\n"
                    + "  /github watch gitee:owner/repo    （Gitee）");
            return;
        }

        String[] addParts = arg.split("\\s+");
        String slug = addParts[0];
        String mcVer = addParts.length > 1 ? addParts[1] : null;
        String loader = addParts.length > 2 ? addParts[2] : null;

        event.reply("正在查询，请稍候...");
        String err = service.addWatch(slug, mcVer, loader);
        if (err != null) {
            event.reply("❌ 添加失败: " + err);
        } else {
            WatchEntry w = service.getWatch(slug.toLowerCase());
            String name = w != null && w.getDisplayName() != null ? w.getDisplayName() : slug;
            event.reply("✅ 已添加 Modrinth 监控\n"
                    + "模组: " + name + "\n"
                    + "版本: " + (mcVer != null ? mcVer : "默认") + " / " + (loader != null ? loader : "默认"));
        }
    }

    private void handleRemove(String[] parts, BotMessageContext event) {
        if (parts.length < 3) {
            event.reply("用法: /modwatch remove <slug>");
            return;
        }
        String key = parts[2].trim().toLowerCase();
        boolean ok = service.removeWatch(key);
        event.reply(ok ? "✅ 已移除监控: " + key : "❌ 「" + key + "」不在监控列表中");
    }

    private void handleCheck(String[] parts, BotMessageContext event) {
        if (parts.length >= 3) {
            String key = parts[2].trim();
            event.reply("正在检查「" + key + "」...");
            String result = service.checkSingle(key);
            event.reply(result);
        } else {
            List<WatchEntry> watches = service.listWatches();
            if (watches.isEmpty()) {
                event.reply("📋 监控列表为空，无内容可检查");
                return;
            }
            event.reply("正在检查 " + watches.size() + " 个模组，请稍候...");
            service.checkAllAsync();
        }
    }

    private void handleFeed(String[] parts, BotMessageContext event) {
        String sub2 = parts.length > 2 ? parts[2].trim().toLowerCase() : "";
        if ("check".equals(sub2)) {
            event.reply("正在检查 Modrinth 新模组，请稍候...");
            int count = service.checkFeedNow();
            if (count < 0) {
                event.reply("⚠️ 新模组发现功能未启用\n在 config.yml 设置 modwatch-feed-enable: true");
            } else if (count == 0) {
                event.reply("✅ 检查完成，暂无新模组");
            } else {
                event.replyMarkdown(service.formatFeedResults(), null);
            }
        } else {
            event.replyMarkdown(service.formatFeedResults(), null);
        }
    }

    private void handleHelp(BotMessageContext event) {
        event.reply("📖 模组更新监控帮助（Modrinth）\n"
                + "─────────────────\n"
                + "/modwatch list — 查看 Modrinth 监控列表\n"
                + "/modwatch add <slug> [mc版本] [加载器] — 添加 Modrinth 监控\n"
                + "/modwatch remove <slug> — 移除 Modrinth 监控\n"
                + "/modwatch check [slug] — 立即检查更新\n"
                + "/modwatch feed — 查看 Modrinth 新模组发现\n"
                + "/modwatch feed check — 立即检查新模组\n"
                + "/modwatch help — 显示此帮助\n"
                + "─────────────────\n"
                + "GitHub / Gitee 仓库追踪请用 /github 命令：\n"
                + "  /github watch owner/repo          （GitHub）\n"
                + "  /github watch gitee:owner/repo    （Gitee）");
    }
}
