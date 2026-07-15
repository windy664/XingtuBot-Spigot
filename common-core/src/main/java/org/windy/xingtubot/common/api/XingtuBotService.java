package org.windy.xingtubot.common.api;

import org.windy.xingtubot.common.command.GroupCommand;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.MessageHandler;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * XingtuBot 对外 API（平台无关）。
 * 其他插件通过此接口与昕途机器人交互，不依赖平台实现。
 *
 * <p>Bukkit 端获取方式：
 * <pre>
 * XingtuBotService api = Bukkit.getServicesManager()
 *         .load(XingtuBotService.class);
 * </pre>
 * Velocity 端获取方式：
 * <pre>
 * XingtuBotService api = XingtuBotVelocity 主类.getService();
 * // 经 proxy.getPluginManager().getPlugin("xingtubotvelocity") 拿主类实例
 * </pre>
 *
 * <p><b>扩展自己的群命令</b>：实现 {@link MessageHandler}（或简化版 {@link GroupCommand}），
 * 调 {@link #registerHandler}/{@link #registerCommand} 注册即可——和昕途内置功能走<b>完全相同</b>的
 * 注册分发路径（参考内置的 WeatherCommand / FortuneCommand 源码就是最好的示例）。
 */
public interface XingtuBotService extends CommandRegistrar, MessageSender, CommandHookBus, BotRuntimeInfo {

    /**
     * API 版本号。每当接口发生不兼容变更时递增；扩展可在启动时校验
     * {@code api.apiVersion() >= 期望值}，避免对着旧/新接口静默炸。
     * <ul>
     *   <li>1 — 初版（hook 用 {@code BiConsumer<String,Object>}，before 无法拦截）</li>
     *   <li>2 — hook 事件类型化为 {@link BotMessageEvent}；before 改 {@link BiPredicate} 可真正拦截</li>
     * </ul>
     */
    int API_VERSION = 3;

    /** 当前实现的 API 版本，便于扩展做兼容性判断。 */
    default int apiVersion() {
        return API_VERSION;
    }

    // ==================== 扩展注册（第三方扩展群命令）====================

    /**
     * 注册一个消息处理器（群命令 / 非命令型功能）。
     * 与昕途内置功能共用同一注册中心：支持优先级、catch-all、菜单自动收集、init/shutdown 生命周期、adminOnly。
     * 可在本插件启动后任意时刻调用（会自动补 init）。
     *
     * @param handler 你的处理器实现
     */
    void registerHandler(MessageHandler handler);

    /**
     * 注册一个简化版群命令（前缀匹配型）。内部自动适配为 {@link MessageHandler}。
     *
     * @param command 你的命令实现
     */
    void registerCommand(GroupCommand command);

    // ==================== 消息发送（markdown-only）====================
    // 产品定位：所有对外消息统一走 Markdown，不提供纯文本发送 API。

    /**
     * 发送 Markdown 消息到指定群。
     *
     * @param groupOpenId       群 openid
     * @param markdownContent   Markdown 内容
     * @param keyboardTemplateId 键盘模板 id（可为 null）
     */
    void sendToGroupMarkdown(String groupOpenId, String markdownContent, String keyboardTemplateId);

    // ==================== 玩家绑定查询 ====================
    // 绑定/玩家数据由「白名单」附属插件（xt-auth）拥有，它已把 BindingService 与
    // BindingRepository 注册到共享服务总线。需要查玩家数据的扩展请走：
    //   BindingRepository repo = ctx.getService(BindingRepository.class);   // 同 classloader
    //   Object repo = ctx.getServiceObject(BindingRepository.class);        // 跨 classloader
    // 核心 API 不再重复暴露这些查询（此前 spigot/velocity 端 store 为 null，是死接口）。

    // ==================== 命令 Hook ====================

    /**
     * 注册命令执行<b>前</b>的 Hook。回调参数：(command 名, {@link BotMessageEvent})。
     * <b>返回 {@code true} 放行，返回 {@code false} 拦截</b>（命令不再执行）。
     * 多个 hook 任一返回 false 即拦截。
     */
    void beforeCommand(BiPredicate<String, ? super BotMessageEvent> hook);

    /**
     * 注册命令执行<b>后</b>的 Hook。回调参数：(command 名, {@link BotMessageEvent})。
     * 仅当命令实际执行（未被 before hook 拦截）时触发。
     */
    void afterCommand(BiConsumer<String, ? super BotMessageEvent> hook);

    /** 获取当前机器人使用的开放平台 AppID */
    String getBotAppId();
}
