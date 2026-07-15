package org.windy.xingtubot.common.qq;

import org.windy.xingtubot.common.platform.BotLogger;

/**
 * QQ OpenAPI transport client.
 *
 * <p>This is the preferred package for QQ-specific transport code. The legacy
 * {@code org.windy.xingtubot.common.api.QqOpenApiClient} remains as the
 * implementation for compatibility during migration.
 */
public class QqOpenApiClient extends org.windy.xingtubot.common.api.QqOpenApiClient {

    public QqOpenApiClient(String appId, String clientSecret) {
        super(appId, clientSecret);
    }

    public QqOpenApiClient(String appId, String clientSecret, boolean sandbox, BotLogger logger) {
        super(appId, clientSecret, sandbox, logger);
    }

    public QqOpenApiClient(String appId, String clientSecret, String apiBase, BotLogger logger) {
        super(appId, clientSecret, apiBase, logger);
    }
}
