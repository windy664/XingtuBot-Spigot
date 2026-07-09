package org.windy.xingtubot.module;

import org.windy.xingtubot.common.api.BotIdentity;
import org.windy.xingtubot.common.handler.impl.GroupChatHandler;
import org.windy.xingtubot.common.module.BotModule;
import org.windy.xingtubot.common.module.ModuleContext;
import org.windy.xingtubot.common.module.capability.GameChatBridge;
import org.windy.xingtubot.common.module.capability.GameEcho;

import java.util.function.Consumer;

/**
 * 群服互联模块：QQ 群 → 游戏聊天桥接（兜底 handler）。
 *
 * <p>本模块注册 {@link GroupChatHandler}，经 {@link GameChatBridge} 能力（主插件提供）把 QQ 群消息广播进游戏。
 *
 * <p>游戏→QQ 方向由平台原生代码处理：
 * <ul>
 *   <li>Bukkit：{@code ChatlinkBukkitPlugin} 创建 {@code ChatreplyModule}
 *       （GameChatForwarder 监听 + /messagereply 命令 + 敏感词过滤）。</li>
 *   <li>Velocity：主插件持有 {@code GroupChatLink}（深度集成 BotCommandHandler/VxtbCommand/QQCommand），
 *       由主插件在 bot 连接后配置 apiClient + allowedGroups。</li>
 * </ul>
 *
 * <p>可选软依赖 xt-auth 的 LockState（白名单锁联动，Bukkit 侧由 ChatlinkBukkitPlugin 获取）。
 */
public final class ChatlinkModule implements BotModule {

    private final Object platformPlugin; // Bukkit JavaPlugin 或 Velocity ProxyServer

    /**
     * 平台广播器：接收一行<b>已带 § 颜色码的完整文本</b>，由平台负责反序列化并发给全体在线玩家
     * （含线程/序列化差异）。用于「机器人消息回显到游戏」；为 null 则不注册回显。
     */
    private Consumer<String> gameBroadcaster;

    /** Bukkit 平台构造。 */
    public ChatlinkModule(Object bukkitPlugin) {
        this.platformPlugin = bukkitPlugin;
    }

    /** Velocity 平台构造。 */
    public ChatlinkModule(Object proxyServer, Void unused) {
        this.platformPlugin = proxyServer;
    }

    /**
     * 注入平台广播器（在 {@link org.windy.xingtubot.common.module.ExtensionBootstrap#enable} 之前调用）。
     * 入参是单行已带 § 颜色码的文本，平台实现负责反序列化并发给全体在线玩家。
     */
    public ChatlinkModule withGameBroadcaster(Consumer<String> broadcaster) {
        this.gameBroadcaster = broadcaster;
        return this;
    }

    @Override
    public String name() {
        return "chatlink";
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        // ===== 机器人消息回显到游戏（独立开关 echo-bot-to-game，与 QQ→游戏 转发分开）=====
        wireGameEcho(ctx);

        if (!ctx.config().getBoolean("chatreply-enable", true)) {
            ctx.logger().info("[Chatlink] 群服互联已禁用。");
            return;
        }

        // ===== QQ→游戏 兜底 handler =====
        GameChatBridge bridge = ctx.getService(GameChatBridge.class);
        if (bridge != null) {
            String prefix = ctx.config().getString("startsWith", "");
            ctx.registry().register(new GroupChatHandler(
                    (event, content) -> bridge.broadcastToGame(event, content), prefix));
        }

        ctx.logger().info("[Chatlink] 群服互联已加载");
    }

    /**
     * 装配「机器人消息回显到游戏」：机器人往群发消息时，同步发一份给在线玩家看。
     *
     * <p>覆盖两条回显路径，注册进核心同一总线（{@code ctx.registry()}/{@code ctx.registerService}）：
     * <ul>
     *   <li>{@link org.windy.xingtubot.common.handler.HandlerRegistry#setGameEcho} —— 命令回复回显；</li>
     *   <li>{@link GameEcho} 服务 —— xt-github / xt-modquery 等主动通知回显。</li>
     * </ul>
     *
     * <p>{@code echo-format} 支持 {@code {bot}} 占位符（替换为机器人昵称 {@link BotIdentity#getName()}），
     * 在<b>每次回显时</b>解析，故 bot 连接拿到真实昵称后立刻生效。
     */
    private void wireGameEcho(ModuleContext ctx) {
        Consumer<String> bc = this.gameBroadcaster;
        if (bc == null) return; // 平台未提供广播器（如该平台不支持）

        final String fmt = ctx.config().getString("echo-format", "§b[{bot}] §f");
        Consumer<String> echo = text -> {
            if (text == null || text.trim().isEmpty()) return;
            String prefix = fmt.replace("{bot}", BotIdentity.getName());
            for (String line : text.split("\n")) {
                if (line.trim().isEmpty()) continue;
                bc.accept(prefix + line);
            }
        };
        ctx.registry().setGameEcho(echo);
        ctx.registerService(GameEcho.class, (GameEcho) echo::accept);
        ctx.logger().info("[Chatlink] 机器人消息回显到游戏已启用");
    }
}
