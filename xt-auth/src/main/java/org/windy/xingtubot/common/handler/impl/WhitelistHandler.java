package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.MessageHandler;

import java.util.concurrent.CompletableFuture;

/**
 * 白名单/登录消息处理：精确匹配「登录」或「绑定」关键词。
 * 「绑定」走 {@link BindingService#bindByAvatar}（下载发送者头像与待验证记录比对）。
 * 依赖 BindingService（仅 Velocity 大脑端启用时生效）。
 * priority=10，仅次于 openid 捕获。
 */
public class WhitelistHandler implements MessageHandler {

    private final BindingService bindingService;
    private final String loginPrompt;
    private final String bindingPrompt;

    public WhitelistHandler(BindingService bindingService, BotConfig config) {
        this.bindingService = bindingService;
        this.loginPrompt = config.getString("login-prompt", "登录");
        this.bindingPrompt = config.getString("binding-prompt", "绑定");
    }

    @Override
    public boolean matches(String message, BotMessageEvent event) {
        if (bindingService == null) return false;
        String trimmed = message.trim();
        // 登录/绑定=精确关键词。不命中则不 match：既不回噪声也不吞消息，其它功能照常。
        return loginPrompt.equals(trimmed) || bindingPrompt.equals(trimmed);
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String trimmed = message.trim();
        String openid = event.getFormId();
        if (loginPrompt.equals(trimmed)) {
            // 来源是不是群里的「登录」按钮点击（INTERACTION_CREATE）。
            boolean fromButton = "INTERACTION_CREATE".equals(event.getEventType());
            CompletableFuture.runAsync(() -> {
                BindingService.Result r = bindingService.loginByGroup(openid);
                // 按钮点击：仅【本人首次成功登录】反馈一次；他人点（已被 QQ 限定拦下/未绑定）、
                // 已登录、离线等一律【静默】，避免群里刷屏。手动发「登录」：照常反馈所有结果。
                if (!fromButton || r.success) {
                    reply(event, r);
                }
            });
        } else {
            // 发「绑定」→ 取发送者头像与待验证记录比对
            CompletableFuture.runAsync(() -> reply(event, bindingService.bindByAvatar(openid)));
        }
    }

    /** 成功卡片走 markdown 通道（不转义），其余文本照常。 */
    private static void reply(BotMessageEvent event, BindingService.Result r) {
        if (r.markdown) {
            event.replyMarkdown(r.message, null);
        } else {
            event.reply(r.message);
        }
    }

    @Override
    public String name() {
        return "whitelist";
    }

    @Override
    public int priority() {
        return 10;
    }

    /** 白名单/登录是关键流程：玩家未必知道要@机器人，故非@消息也处理。 */
    @Override
    public boolean acceptsWithoutMention() {
        return true;
    }
}
