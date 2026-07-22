package org.windy.xingtubot.common.bot;

import org.windy.xingtubot.common.config.BotConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时的配置体检：检查 gateway 模式必需配置，给出<b>精准、可操作</b>的提示，
 * 取代「返回 null + 笼统警告」。专为「卖给别人、买家会填错」的场景设计。
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

    /** 校验 gateway 模式配置（只需 app-id + secret）。 */
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


        return r;
    }
}
