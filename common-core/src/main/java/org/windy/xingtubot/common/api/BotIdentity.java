package org.windy.xingtubot.common.api;

/**
 * 机器人身份（昵称）的单一来源。
 *
 * <p>取代旧的 {@code config.yml: bot-name}：昵称是 QQ 官方下发的（bot 连接后从 {@code /users/@me}
 * 的 {@code username} 拿到），不该再让用户手填。{@code QQGatewayClient} 解析到名字后调
 * {@link #setName(String)}，之后所有 {@code {bot}} 占位符、菜单标题、白名单提示等读 {@link #getName()}。
 *
 * <p>放在 common-core（被 bundle shade、各扩展 compileOnly），运行期由 bundle 的 classloader 持有
 * <b>同一份</b>静态实例，故 bundle setName 后，各附属插件 getName 也能读到——单一真源、跨插件可见。
 *
 * <p>API 名字是异步晚到的：连接成功前 {@link #getName()} 返回默认「机器人」，解析后即为真实昵称。
 * 所有读取点都应在<b>使用时</b>读 {@link #getName()}，不要在构造期缓存，否则拿到的是旧默认值。
 */
public final class BotIdentity {

    private static volatile String name = "机器人";

    private BotIdentity() {
    }

    /** 当前机器人昵称（默认「机器人」；bot 连接后为 QQ 官方昵称）。 */
    public static String getName() {
        return name;
    }

    /** 由 {@code QQGatewayClient} 在解析到 QQ 官方昵称后调用。空/空白忽略。 */
    public static void setName(String n) {
        if (n != null && !n.trim().isEmpty()) {
            name = n.trim();
        }
    }
}
