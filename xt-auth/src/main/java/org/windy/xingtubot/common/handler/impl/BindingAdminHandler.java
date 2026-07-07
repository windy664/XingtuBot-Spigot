package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.binding.BindingEntry;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.MessageHandler;
import org.windy.xingtubot.common.util.Md;

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
public class BindingAdminHandler implements MessageHandler {

    private static final int LIST_LIMIT = 40; // 列表最多展示条数，超出给出提示

    private final Supplier<BindingRepository> repo;

    public BindingAdminHandler(Supplier<BindingRepository> repo) {
        this.repo = repo;
    }

    @Override
    public boolean matches(String message, BotMessageEvent event) {
        String m = message.trim();
        return m.equals("绑定列表") || m.startsWith("查绑定") || m.startsWith("解绑");
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String m = message.trim();
        CompletableFuture.runAsync(() -> {
            BindingRepository store = repo.get();
            if (store == null) {
                event.replyMarkdown(Md.card("⚠️", "绑定服务不可用")
                        .quote("白名单未启用，或绑定库尚未就绪").build(), null);
                return;
            }
            if (m.equals("绑定列表")) {
                listAll(store, event);
            } else if (m.startsWith("查绑定")) {
                query(store, event, m.substring("查绑定".length()).trim());
            } else { // 解绑
                unbind(store, event, m.substring("解绑".length()).trim());
            }
        });
    }

    private void listAll(BindingRepository store, BotMessageEvent event) {
        List<BindingEntry> all = store.all();
        if (all == null || all.isEmpty()) {
            event.replyMarkdown(Md.card("👑", "绑定列表")
                    .quote("当前还没有任何白名单绑定").build(), null);
            return;
        }
        Md card = Md.card("👑", "绑定列表（" + all.size() + "）");
        int shown = Math.min(all.size(), LIST_LIMIT);
        for (int i = 0; i < shown; i++) {
            BindingEntry e = all.get(i);
            card.line((i + 1) + ". **" + safe(e.player) + "**　🐧 " + safe(e.qq));
        }
        if (all.size() > LIST_LIMIT) {
            card.quote("仅显示前 " + LIST_LIMIT + " 条，共 " + all.size()
                    + " 条。用「查绑定 <玩家/QQ>」精确查询");
        } else {
            card.quote("查单个：查绑定 <玩家/QQ>　·　解绑：解绑 <玩家>");
        }
        event.replyMarkdown(card.build(), null);
    }

    private void query(BindingRepository store, BotMessageEvent event, String arg) {
        if (arg.isEmpty()) {
            event.replyMarkdown(Md.card("🔎", "查绑定")
                    .quote("用法：查绑定 <玩家名 或 QQ号>").build(), null);
            return;
        }
        BindingEntry e = store.findByPlayer(arg);
        if (e == null) e = findByQq(store, arg); // 玩家名查不到再按 QQ 号找
        if (e == null) {
            event.replyMarkdown(Md.card("🔎", "查绑定")
                    .field("🔍", "关键词", arg)
                    .quote("没找到该玩家名 / QQ 号的绑定").build(), null);
            return;
        }
        event.replyMarkdown(Md.card("🔎", "绑定信息")
                .field("👤", "玩家", e.player)
                .field("🐧", "QQ", e.qq)
                .field("🆔", "openid", shortId(e.openid))
                .field("🕒", "绑定于", e.time)
                .build(), null);
    }

    private void unbind(BindingRepository store, BotMessageEvent event, String player) {
        if (player.isEmpty()) {
            event.replyMarkdown(Md.card("🔓", "解绑")
                    .quote("用法：解绑 <玩家名>").build(), null);
            return;
        }
        BindingEntry e = store.findByPlayer(player);
        if (e == null) {
            event.replyMarkdown(Md.card("🔓", "解绑")
                    .field("👤", "玩家", player)
                    .quote("该玩家没有绑定记录，无需解绑").build(), null);
            return;
        }
        boolean removed = store.removeByPlayer(player);
        if (removed) {
            event.replyMarkdown(Md.card("✅", "已解绑")
                    .field("👤", "玩家", e.player)
                    .field("🐧", "原 QQ", e.qq)
                    .quote("该玩家下次进服需重新绑定白名单").build(), null);
        } else {
            event.replyMarkdown(Md.card("⚠️", "解绑失败")
                    .field("👤", "玩家", player)
                    .quote("删除记录时出错，请查看后台日志").build(), null);
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
        return Arrays.asList("绑定列表", "查绑定", "解绑");
    }

    @Override
    public String usage() {
        return "绑定列表 / 查绑定 <玩家|QQ> / 解绑 <玩家>";
    }

    @Override
    public String description() {
        return "白名单绑定管理（列表/查询/解绑）";
    }

    @Override
    public String category() {
        return "👑 管理";
    }
}
