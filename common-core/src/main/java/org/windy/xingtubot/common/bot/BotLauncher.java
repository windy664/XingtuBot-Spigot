package org.windy.xingtubot.common.bot;

import com.google.gson.Gson;
import org.windy.xingtubot.common.api.QqOpenApiClient;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.messenger.MessengerConnectionState;
import org.windy.xingtubot.common.messenger.OfficialBotMessenger;
import org.windy.xingtubot.common.messenger.PlatformMessenger;
import org.windy.xingtubot.common.platform.PlatformAdapter;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.poll.QQGatewayClient;
import org.windy.xingtubot.common.poll.QqBot;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 平台无关的启动辅助：解析通信模式、按配置构建机器人。
 *
 * <p>支持以下协议：
 * <ul>
 *   <li>{@code GATEWAY} — QQ 官方 WebSocket 网关 + OpenAPI 回复</li>
 *   <li>{@code ONEBOT11} — OneBot 11 协议（正向 WS 收事件 + HTTP API 发送）</li>
 *   <li>{@code OFF} — 不启动机器人通信</li>
 * </ul>
 */
public final class BotLauncher {

    public enum Mode { GATEWAY, ONEBOT11, OFF }

    /** gateway 模式的构建结果。 */
    public static class GatewayResult {
        public final QqBot bot;
        public final QQGatewayClient gatewayClient;
        public final OfficialBotMessenger messenger;
        public GatewayResult(QqBot bot, QQGatewayClient gatewayClient, OfficialBotMessenger messenger) {
            this.bot = bot;
            this.gatewayClient = gatewayClient;
            this.messenger = messenger;
        }
    }

    private BotLauncher() {
    }

    /**
     * 本节点是否运行 bot，由统一拓扑键 {@code server-role} 决定：
     * {@code off/none/disable} = 不运行；其余 = 运行。
     *
     * <p>注：Bukkit 子服的「手脚」判定（server-role=slave 或 auto 探测到代理）在平台侧
     * （XingtuBot.resolveSlave）先行 gate——是手脚就根本不调本方法。故本方法只回答
     * 「非手脚节点要不要起 bot」。
     *
     * <p>协议选择由配置键 {@code qq-protocol} 决定（{@code official | onebot11}，默认 {@code official}）。
     * 当 {@code server-role=off} 时无论协议配置如何都返回 {@code OFF}。
     */
    public static Mode resolveMode(BotConfig config) {
        String role = config.getString("server-role", "auto").trim().toLowerCase();
        String legacy = config.getString("bot-mode", "").trim().toLowerCase();
        if (isDisabled(role) || isDisabled(legacy)) {
            return Mode.OFF;
        }
        // 从 qq-protocol 键决定协议类型：official / onebot11
        String protocol = config.getString("qq-protocol", "official").trim().toLowerCase();
        if ("onebot11".equals(protocol)) {
            return Mode.ONEBOT11;
        }
        return Mode.GATEWAY;
    }

    private static boolean isDisabled(String v) {
        return v.equals("off") || v.equals("none") || v.equals("disable");
    }

    /**
     * 构建 gateway 模式机器人（QQ 官方 WebSocket 网关 + OpenAPI 回复）。
     *
     * @return 构建结果（含 bot、gatewayClient、messenger）；配置缺失返回 null。
     */
    public static GatewayResult buildGateway(BotConfig config, PlatformAdapter adapter,
                                             BotLogger logger, Consumer<BotMessageEvent> listener) {
        ConfigValidator.Result vr = ConfigValidator.validateGateway(config);
        for (String w : vr.warnings) adapter.log("[配置提醒] " + w);
        if (!vr.ok()) {
            for (String e : vr.errors) adapter.log("[配置错误] " + e);
            return null;
        }

        String appId = config.getString("openapi-app-id", "").trim();
        String secret = config.getString("openapi-client-secret", "").trim();
        boolean sandbox = config.getBoolean("openapi-sandbox", false);

        QqOpenApiClient api = new QqOpenApiClient(appId, secret,
                sandbox ? QqOpenApiClient.API_SANDBOX : QqOpenApiClient.API_PROD, null);

        // 群白名单：["*"] 或空 = 全部允许，否则只响应列表中的群
        List<String> allowedList = config.getStringList("allowed-groups");
        Set<String> allowedGroups = allowedList.isEmpty()
                ? Collections.singleton("*") : new HashSet<>(allowedList);

        // 创建 OfficialBotMessenger（平台适配器）
        OfficialBotMessenger messenger = new OfficialBotMessenger(api);
        messenger.setState(MessengerConnectionState.CONNECTING);

        // 构建 bot（事件由 gatewayClient 注入）
        QqBot bot = new QqBot(adapter, api, messenger, allowedGroups);
        bot.addMessageListener(listener);

        // 框架级调试开关
        org.windy.xingtubot.common.api.DebugFlag.bind(() -> config.getBoolean("debug", false));

        // 构建 gateway 客户端
        QQGatewayClient gwClient = new QQGatewayClient(appId, secret,
                bot::handleExternalEvent, logger, () -> config.getBoolean("debug", false));

        // gateway 就绪回调：更新 messenger 状态
        gwClient.setOnReady(() -> messenger.setState(MessengerConnectionState.READY));

        return new GatewayResult(bot, gwClient, messenger);
    }

    /**
     * 构建 OneBot 11 模式机器人。
     * <p>由 {@code OneBot11Messenger} 统一组装网关、翻译器、API 客户端和回复器。
     *
     * @return 包含 bot（事件消费者）和 messenger 的结果；配置缺失返回 null。
     */
    public static OneBotResult buildOnebot11(BotConfig config, PlatformAdapter adapter,
                                             BotLogger logger, Consumer<BotMessageEvent> listener) {
        ConfigValidator.Result vr = ConfigValidator.validateOnebot11(config);
        for (String w : vr.warnings) adapter.log("[配置提醒] " + w);
        if (!vr.ok()) {
            for (String e : vr.errors) adapter.log("[配置错误] " + e);
            return null;
        }

        String wsToken = config.getString("onebot.ws-token", "").trim();
        String forwardUrl = config.getString("onebot.forward-url", "").trim();
        String apiUrl = config.getString("onebot.api-url", "").trim();
        String httpToken = config.getString("onebot.http-token", "").trim();
        long heartbeatMs = config.getInt("onebot.heartbeat-interval", 30000);

        // 使用 OneBot11Messenger 统一组装四件套
        org.windy.xingtubot.common.onebot.OneBot11Messenger messenger =
                new org.windy.xingtubot.common.onebot.OneBot11Messenger(
                        forwardUrl, apiUrl, wsToken, httpToken,
                        heartbeatMs, new Gson(), logger, listener);

        return new OneBotResult(messenger);
    }

    /**
     * OneBot 11 模式的构建结果。
     */
    public static final class OneBotResult {
        public final org.windy.xingtubot.common.onebot.OneBot11Messenger messenger;

        public OneBotResult(org.windy.xingtubot.common.onebot.OneBot11Messenger messenger) {
            this.messenger = messenger;
        }
    }
}
