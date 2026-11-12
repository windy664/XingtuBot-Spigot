package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.platform.BotLogger;

import java.util.Arrays;
import java.util.List;

/**
 * AI 管理命令：{@code ai 用量} / {@code ai reload} / {@code ai 身份}。
 *
 * <p>用法与其它模块的命令一致：直接发文本（{@code ai 用量}）或带斜杠（{@code /ai 用量}）都认。
 *
 * <ul>
 *   <li>{@code ai 用量}：当日调用次数 / token 用量 / 分群明细</li>
 *   <li>{@code ai reload}：热重载配置（黑名单、攻击名单、人格卡、额度…），不用重启服务器</li>
 *   <li>{@code ai 身份}：在当前群重发一次 AI 身份标识（合规要求）</li>
 * </ul>
 */
public final class AiAdminCommand implements BotCommand {

    private final BotConfig config;
    private final AiChatHandler handler;
    private final AiUsage usage;
    private final BotLogger logger;

    public AiAdminCommand(BotConfig config, AiChatHandler handler, AiUsage usage, BotLogger logger) {
        this.config = config;
        this.handler = handler;
        this.usage = usage;
        this.logger = logger;
    }

    @Override
    public boolean matches(String message) {
        if (message == null) return false;
        String m = message.trim();
        if (m.startsWith("/")) m = m.substring(1).trim();
        return isUsage(m) || isReload(m) || isIdentity(m);
    }

    private static boolean isUsage(String m) {
        return m.equals("ai 用量") || m.equals("ai usage") || m.equals("ai token");
    }

    private static boolean isReload(String m) {
        return m.equals("ai reload") || m.equals("ai 重载") || m.equals("ai 刷新配置");
    }

    private static boolean isIdentity(String m) {
        return m.equals("ai 身份") || m.equals("ai 标识");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String m = message.trim();
        if (m.startsWith("/")) m = m.substring(1).trim();

        if (isUsage(m)) {
            usage.save();
            event.reply(usage.render());
            return;
        }
        if (isReload(m)) {
            try {
                config.reload();      // 重新读盘
            } catch (Exception e) {
                if (logger != null) logger.warn("[AI] 配置重载失败：" + e.getMessage());
                event.reply("配置重新读取失败：" + e.getMessage());
                return;
            }
            handler.reloadConfig();  // 重新应用到各组件
            event.reply("AI 配置已重载，黑名单 / 攻击名单 / 人格 / 额度都已生效");
            return;
        }
        if (isIdentity(m)) {
            event.reply(handler.identityNoticeText());
        }
    }

    @Override
    public String name() { return "ai-admin"; }

    @Override
    public boolean adminOnly() { return false; }

    @Override
    public List<String> triggers() {
        return Arrays.asList("ai 用量", "ai reload", "ai 身份");
    }

    @Override
    public String usage() {
        // 群内消息会被 mention 门控拦掉（除非带斜杠或管理员），
        // 所以在群里要用 "/ai 用量" 这种写法
        return "ai 用量 / ai reload / ai 身份（群内请加斜杠：/ai 用量）";
    }

    @Override
    public String description() { return "AI 用量查看、配置热重载、身份标识"; }
    // category 自动继承模块 displayName
}
