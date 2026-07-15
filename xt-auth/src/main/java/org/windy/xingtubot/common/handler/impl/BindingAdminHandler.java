package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.binding.BindingEntry;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;
import org.windy.xingtubot.common.util.Md;
import org.windy.xingtubot.common.whitelist.LockMessages;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 白名单绑定 · 超管管理命令（仅 {@code admin-openids} 可用）。
 *
 * <ul>
 *   <li>{@code 绑定列表} —— 列出全部绑定（玩家 ↔ QQ）。</li>
 *   <li>{@code 查绑定 <玩家名|QQ号>} —— 查单个绑定详情。</li>
 *   <li>{@code 解绑 <玩家名>} —— 解除某玩家绑定（其下次进服需重新绑定）。</li>
 * </ul>
 *
 * <p>平台无关：{@link BindingRepository} 经 supplier 懒取（本地模式由 WhitelistModule、
 * 大脑模式由 AuthModule 注册到服务总线），注册进共享 {@code HandlerRegistry} 后两种部署通用。
 * 回复统一走 markdown 卡片，与绑定/登录成功卡片同一审美。
 */
public class BindingAdminHandler implements BotMessageHandler {

    private static final int LIST_LIMIT = 40; // 列表最多展示条数，超出给出提示

    private final Supplier<BindingRepository> repo;

    public BindingAdminHandler(Supplier<BindingRepository> repo) {
        this.repo = repo;
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        String m = message.trim();
        String list = LockMessages.get("admin-trigger-list");
        String query = LockMessages.get("admin-trigger-query");
        String unbind = LockMessages.get("admin-trigger-unbind");
        return m.equals(list) || m.startsWith(query) || m.startsWith(unbind);
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String m = message.trim();
        String list = LockMessages.get("admin-trigger-list");
        String query = LockMessages.get("admin-trigger-query");
        String unbind = LockMessages.get("admin-trigger-unbind");
        CompletableFuture.runAsync(() -> {
            BindingRepository store = repo.get();
            if (store == null) {
                event.replyMarkdown(Md.card("⚠️", LockMessages.get("admin-card-title-unavailable"))
                        .quote(LockMessages.get("admin-service-unavailable")).build(), null);
                return;
            }
            if (m.equals(list)) {
                listAll(store, event);
            } else if (m.startsWith(query)) {
                query(store, event, m.substring(query.length()).trim());
            } else { // 解绑
                unbind(store, event, m.substring(unbind.length()).trim());
            }
        });
    }

    private void listAll(BindingRepository store, BotMessageContext event) {
        List<BindingEntry> all = store.all();
        String title = LockMessages.get("admin-card-title-list");
        if (all == null || all.isEmpty()) {
            event.replyMarkdown(Md.card("👑", title)
                    .quote(LockMessages.get("admin-list-empty")).build(), null);
            return;
        }
        Md card = Md.card("👑", title + "（" + all.size() + "）");
        int shown = Math.min(all.size(), LIST_LIMIT);
        for (int i = 0; i < shown; i++) {
            BindingEntry e = all.get(i);
            card.line((i + 1) + ". **" + safe(e.player) + "**　🐧 " + safe(e.qq));
        }
        if (all.size() > LIST_LIMIT) {
            card.quote(LockMessages.adminListTruncated(LIST_LIMIT, all.size()));
        } else {
            card.quote(LockMessages.get("admin-list-hint"));
        }
        event.replyMarkdown(card.build(), null);
    }

    private void query(BindingRepository store, BotMessageContext event, String arg) {
        if (arg.isEmpty()) {
            event.replyMarkdown(Md.card("🔎", LockMessages.get("admin-card-title-query"))
                    .quote(LockMessages.get("admin-query-usage")).build(), null);
            return;
        }
        BindingEntry e = store.findByPlayer(arg);
        if (e == null) e = findByQq(store, arg); // 玩家名查不到再按 QQ 号找
        if (e == null) {
            event.replyMarkdown(Md.card("🔎", LockMessages.get("admin-card-title-query"))
                    .field("🔍", LockMessages.get("admin-field-keyword"), arg)
                    .quote(LockMessages.get("admin-query-not-found")).build(), null);
            return;
        }
        event.replyMarkdown(Md.card("🔎", LockMessages.get("admin-card-title-info"))
                .field("👤", LockMessages.get("admin-field-player"), e.player)
                .field("🐧", LockMessages.get("admin-field-qq"), e.qq)
                .field("🆔", LockMessages.get("admin-field-openid"), shortId(e.openid))
                .field("🕒", LockMessages.get("admin-field-bound-at"), e.time)
                .build(), null);
    }

    private void unbind(BindingRepository store, BotMessageContext event, String player) {
        if (player.isEmpty()) {
            event.replyMarkdown(Md.card("🔓", LockMessages.get("admin-card-title-unbind"))
                    .quote(LockMessages.get("admin-unbind-usage")).build(), null);
            return;
        }
        BindingEntry e = store.findByPlayer(player);
        if (e == null) {
            event.replyMarkdown(Md.card("🔓", LockMessages.get("admin-card-title-unbind"))
                    .field("👤", LockMessages.get("admin-field-player"), player)
                    .quote(LockMessages.get("admin-unbind-not-found")).build(), null);
            return;
        }
        boolean removed = store.removeByPlayer(player);
        if (removed) {
            event.replyMarkdown(Md.card("✅", LockMessages.get("admin-card-title-unbound"))
                    .field("👤", LockMessages.get("admin-field-player"), e.player)
                    .field("🐧", LockMessages.get("admin-field-original-qq"), e.qq)
                    .quote(LockMessages.get("admin-unbind-done")).build(), null);
        } else {
            event.replyMarkdown(Md.card("⚠️", LockMessages.get("admin-card-title-unbind-fail"))
                    .field("👤", LockMessages.get("admin-field-player"), player)
                    .quote(LockMessages.get("admin-unbind-error")).build(), null);
        }
    }

    private BindingEntry findByQq(BindingRepository store, String qq) {
        List<BindingEntry> all = store.all();
        if (all == null) return null;
        for (BindingEntry e : all) {
            if (qq.equals(e.qq)) return e;
        }
        return null;
    }

    private static String shortId(String openid) {
        if (openid == null || openid.length() <= 12) return openid;
        return openid.substring(0, 8) + "…" + openid.substring(openid.length() - 4);
    }

    private static String safe(String s) {
        return s == null ? "—" : s;
    }

    @Override
    public String name() {
        return "binding-admin";
    }

    @Override
    public int priority() {
        return 20; // 在 WhitelistHandler(10) 之后、普通命令之前
    }

    @Override
    public boolean adminOnly() {
        return true;
    }

    @Override
    public List<String> triggers() {
        return LockMessages.adminTriggers();
    }

    @Override
    public String usage() {
        return LockMessages.get("admin-usage");
    }

    @Override
    public String description() {
        return LockMessages.get("admin-desc");
    }

    @Override
    public String category() {
        return LockMessages.get("admin-category");
    }
}
