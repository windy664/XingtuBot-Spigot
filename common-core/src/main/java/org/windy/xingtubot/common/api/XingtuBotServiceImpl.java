package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.queue.PendingMessageQueue;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * XingtuBotService 默认实现。
 * 由平台侧（Spigot/Velocity）构造并注入依赖。
 */
public class XingtuBotServiceImpl implements XingtuBotService {

    // Markdown 发送器：(groupOpenId, content, keyboardId) -> 实际发送
    // markdown-only：群消息一律走 markdown，无纯文本发送器。
    private final GroupMarkdownSender markdownSender;
    // 主动消息客户端（可 null）：发送时尽力先走它，走不通再回退 sender/队列
    private volatile QqOpenApiClient apiClient;
    // 注册中心：供第三方 registerHandler/registerCommand 转发（平台侧注入）
    private volatile org.windy.xingtubot.common.handler.HandlerRegistry registry;

    private final CopyOnWriteArrayList<BiPredicate<String, BotMessageEvent>> beforeHooks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BiConsumer<String, BotMessageEvent>> afterHooks = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    public interface GroupMarkdownSender {
        void send(String groupOpenId, String content, String keyboardTemplateId);
    }

    public XingtuBotServiceImpl(GroupMarkdownSender markdownSender) {
        this.markdownSender = markdownSender;
    }

    // ==================== 扩展注册 ====================

    /** 注入注册中心（平台侧在建好 HandlerRegistry 后调用，使 registerHandler 可用）。 */
    public void setRegistry(org.windy.xingtubot.common.handler.HandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void registerHandler(org.windy.xingtubot.common.handler.MessageHandler handler) {
        org.windy.xingtubot.common.handler.HandlerRegistry r = this.registry;
        if (r != null && handler != null) {
            r.register(handler);
        }
    }

    @Override
    public void registerCommand(org.windy.xingtubot.common.command.GroupCommand command) {
        org.windy.xingtubot.common.handler.HandlerRegistry r = this.registry;
        if (r != null && command != null) {
            r.register(command);
        }
    }

    // ==================== 消息发送 ====================

    /** 设置主动消息客户端（apiClient 就绪后由平台侧注入）。 */
    public void setApiClient(QqOpenApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public void sendToGroupMarkdown(String groupOpenId, String markdownContent, String keyboardTemplateId) {
        if (groupOpenId == null || markdownContent == null) return;
        // 1) 尽力先走主动消息（主动 markdown 不带按钮模板）
        QqOpenApiClient api = this.apiClient;
        if (api != null) {
            try {
                api.sendProactiveGroupMarkdown(groupOpenId, markdownContent);
                return;
            } catch (Exception ignored) {
                // 主动失败 → 往下回退
            }
        }
        // 2) 平台自定义发送通道（可带按钮模板）
        if (markdownSender != null) {
            markdownSender.send(groupOpenId, markdownContent, keyboardTemplateId);
            return;
        }
        // 3) 兜底：挂起队列（按文本推送，markdown 语法会原样显示）
        PendingMessageQueue.getInstance().offer(groupOpenId, markdownContent);
    }

    // 玩家绑定查询已下放到「白名单」附属插件（xt-auth），经服务总线提供
    // BindingService / BindingRepository。核心 API 不再重复暴露。

    // ==================== 命令 Hook ====================

    @Override
    public void beforeCommand(BiPredicate<String, BotMessageEvent> hook) {
        if (hook != null) beforeHooks.add(hook);
    }

    @Override
    public void afterCommand(BiConsumer<String, BotMessageEvent> hook) {
        if (hook != null) afterHooks.add(hook);
    }

    /**
     * 触发 before hooks。任一 hook 返回 false 即<b>拦截</b>（返回 false，命令不再执行）。
     * 由 HandlerRegistry 在执行命令前调用。hook 自身抛异常按「放行」处理，不影响其它 hook。
     */
    public boolean fireBeforeCommand(String command, BotMessageEvent event) {
        boolean allow = true;
        for (BiPredicate<String, BotMessageEvent> hook : beforeHooks) {
            try {
                if (!hook.test(command, event)) {
                    allow = false; // 继续跑完其余 hook（让它们有机会观察），但最终拦截
                }
            } catch (Exception ignored) {
            }
        }
        return allow;
    }

    /**
     * 触发 after hooks。
     * 由 HandlerRegistry 在执行命令后调用。
     */
    public void fireAfterCommand(String command, BotMessageEvent event) {
        for (BiConsumer<String, BotMessageEvent> hook : afterHooks) {
            try {
                hook.accept(command, event);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public String getBotAppId() {
        return this.apiClient != null ? this.apiClient.getAppId() : "";
    }
}
