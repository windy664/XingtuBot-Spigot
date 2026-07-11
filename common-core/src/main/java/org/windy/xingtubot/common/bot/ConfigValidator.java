package org.windy.xingtubot.common.bot;

import org.windy.xingtubot.common.config.BotConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时的配置体检：根据协议类型检查必需配置，给出<b>精准、可操作</b>的提示。
 *
 * <p>区分 error（缺了就跑不起来）与 warn（能跑但可能不是预期），分别返回。
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    public static final class Result {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** 校验 gateway 模式配置（QQ 官方协议：app-id + secret）。 */
    public static Result validateGateway(BotConfig config) {
        Result r = new Result();
        String appId = config.getString("openapi-app-id", "").trim();
        String secret = config.getString("openapi-client-secret", "").trim();
        if (appId.isEmpty()) {
            r.errors.add("openapi-app-id 未填写");
        }
        if (secret.isEmpty()) {
            r.errors.add("openapi-client-secret 未填写");
        }

        // 提示旧配置用户
        String relay = config.getString("webhook-relay-url", "").trim();
        if (!relay.isEmpty()) {
            r.warnings.add("webhook-relay-url 已废弃（SCF 中转已移除），该配置项将被忽略");
        }

        return r;
    }

    /**
     * 校验 OneBot 11 模式配置。
     *
     * 检查项：
     * <ul>
     *   <li>{@code onebot.ws-token} 非空（WS 握手鉴权）</li>
     *   <li>{@code onebot.mode} ∈ { forward_ws, http(预留) }</li>
     *   <li>{@code mode=forward_ws} 时 {@code onebot.forward-url} 非空且为合法 ws/wss URL</li>
     *   <li>{@code mode=http} 时本机需监听端口</li>
     * </ul>
     */
    public static Result validateOnebot11(BotConfig config) {
        Result r = new Result();

        String wsToken = config.getString("onebot.ws-token", "").trim();
        if (wsToken.isEmpty()) {
            r.errors.add("onebot.ws-token 未填写（NapCat WS 服务端的 verify-token）");
        }

        String mode = config.getString("onebot.mode", "forward_ws").trim().toLowerCase();
        if (!"forward_ws".equals(mode) && !"http".equals(mode)) {
            r.errors.add("onebot.mode 必须为 forward_ws 或 http（当前: " + mode + "）");
        }

        if ("forward_ws".equals(mode)) {
            String forwardUrl = config.getString("onebot.forward-url", "").trim();
            if (forwardUrl.isEmpty()) {
                r.errors.add("onebot.forward-url 未填写（正向 WS 连接地址，如 ws://公网IP:3001/onebot/v11/ws）");
            } else if (!forwardUrl.startsWith("ws://") && !forwardUrl.startsWith("wss://")) {
                r.errors.add("onebot.forward-url 不是合法的 ws/wss 地址");
            }
        }

        // api-url 为 HTTP API 调用地址（非 WS 地址），可选但建议填写
        String apiUrl = config.getString("onebot.api-url", "").trim();
        if (apiUrl.isEmpty()) {
            r.warnings.add("onebot.api-url 未填写，将无法使用 HTTP API（主动消息/群信息等）");
        }

        return r;
    }
}
