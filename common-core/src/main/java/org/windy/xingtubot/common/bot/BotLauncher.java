package org.windy.xingtubot.common.bot;

import org.windy.xingtubot.common.api.QqOpenApiClient;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageEvent;
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
 * <p>当前仅支持 {@code gateway} 模式（QQ 官方 WebSocket 网关 + OpenAPI 回复）。
 * 旧的 SCF 轮询模式（webhook）已移除。
 */
public final class BotLauncher {

    public enum Mode { GATEWAY, OFF }

    /** gateway 模式的构建结果：bot + gatewayClient。 */
    public static class GatewayResult {
        public final QqBot bot;
        public final QQGatewayClient gatewayClient;
        public GatewayResult(QqBot bot, QQGatewayClient gatewayClient) {
            this.bot = bot;
            this.gatewayClient = gatewayClient;
        }
    }

    private BotLauncher() {
    }

    /**
     * 本节点是否运行 QQ bot（即是否为大脑），由统一拓扑键 {@code server-role} 决定：
     * {@code off/none/disable} = 不运行；其余（auto/local/brain/slave/空）= 运行。
     *
     * <p>注：Bukkit 子服的「手脚」判定（server-role=slave 或 auto 探测到代理）在平台侧
     * （XingtuBot.resolveSlave）先行 gate——是手脚就根本不调本方法。故本方法只回答
     * 「非手脚节点要不要起 bot」：代理端默认起（大脑），单机默认起，填 off 才不起。
     *
     * <p>兼容已废弃的 {@code bot-mode}：旧配置里 {@code bot-mode: off} 仍视为关（迁移期，
     * 新配置请用 server-role；bot-mode 键已从默认 config 移除）。
     */
    public static Mode resolveMode(BotConfig config) {
        String role = config.getString("server-role", "auto").trim().toLowerCase();
        String legacy = config.getString("bot-mode", "").trim().toLowerCase(); // 已废弃，仅兼容旧配置
        if (isDisabled(role) || isDisabled(legacy)) {
            return Mode.OFF;
        }
        return Mode.GATEWAY;
    }

    private static boolean isDisabled(String v) {
        return v.equals("off") || v.equals("none") || v.equals("disable");
    }

    /**
     * 构建 gateway 模式机器人（QQ 官方 WebSocket 网关 + OpenAPI 回复）。
     *
     * @return 构建结果（含 bot 和 gatewayClient）；配置缺失返回 null。
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

        // markdown-only：所有回复统一走 markdown 通道（产品定位，要求 bot 具备 markdown 权限）。

        // 构建 bot（事件由 gatewayClient 注入）
        QqBot bot = new QqBot(adapter, api, allowedGroups);
        bot.addMessageListener(listener);

        // 框架级调试开关单一来源：绑定后各附属插件（如 MCMOD 爬取）经 DebugFlag.isOn() 实时跟随。
        org.windy.xingtubot.common.DebugFlag.bind(() -> config.getBoolean("debug", false));

        // 构建 gateway 客户端（debug 决定是否打逐事件类型日志）
        QQGatewayClient gwClient = new QQGatewayClient(appId, secret,
                bot::handleExternalEvent, logger, () -> config.getBoolean("debug", false));

        return new GatewayResult(bot, gwClient);
    }
}
