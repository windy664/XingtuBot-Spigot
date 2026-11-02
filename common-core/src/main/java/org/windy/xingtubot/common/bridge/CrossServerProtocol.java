package org.windy.xingtubot.common.bridge;

/**
 * Velocity（大脑）与 Spigot（手脚）之间的 Plugin Message 协议。
 *
 * <p>背景：白名单/登录要操作 AuthMe（只在 Spigot 子服），而机器人 webhook 在 Velocity。
 * 两端通过插件消息通道通信，实现「大脑在 Velocity 收群消息+比对、手脚在 Spigot 执行 AuthMe」。
 *
 * <p>消息体统一为 UTF 字段：第一个字段是 {@link Type} 名称，其后字段按各类型约定。
 * 通道走玩家连接传输——这些场景（绑定/登录）玩家本就在线，约束自然满足。
 */
public final class CrossServerProtocol {

    /** 插件消息通道名（namespace:name，现代格式）。 */
    public static final String CHANNEL = "xingtu:bridge";

    private CrossServerProtocol() {
    }

    /** 消息类型。 */
    public enum Type {
        // ===== Spigot → Velocity（手脚上报大脑）=====
        /** 握手探测：子服问代理「机器人是不是你在管」。字段：server 名。 */
        WHO_IS_BOSS,

        // ===== Velocity → Spigot（大脑派活给手脚）=====
        /** 代理回应握手：机器人由我（代理）主导，子服请当手脚。字段：代理端注册名。 */
        I_AM_BOSS,

        // ===== 后台命令（超管群里控服）=====
        /** 大脑广播控制台命令；子服匹配自己的 server-name 才执行。字段：targetServerName, requestId, command。 */
        DO_CONSOLE,
        /** 子服回传命令执行输出给大脑。字段：requestId, serverName, output。 */
        CONSOLE_RESULT,

        // ===== PAPI 占位符跨服解析 =====
        /** 大脑请求子服用 PAPI 解析占位符。字段：requestId, 玩家名, 含%占位符%的文本。 */
        PAPI_RESOLVE,
        /** 子服回传解析后的文本。字段：requestId, 解析结果。 */
        PAPI_RESULT,

        // ===== 子服自动发现 =====
        /** 代理端广播子服注册表（name=address 对），子服按端口匹配自动发现自己的代理名。字段：registryStr。 */
        SERVER_REGISTRY

        // ===== 历史遗留类型已移除 =====
        // 自研锁改为代理端 packetevents 闭环后，以下类型无发送方或无接收方，已删除：
        // PLAYER_JOIN / PLAYER_QUIT / DECLARE_QQ（手脚上报，大脑不再消费）
        // NEED_QQ / CLEAR_QQ / DO_REGISTER / DO_LOGIN / MSG_PLAYER（大脑派活，改为代理层直控）
        // QUERY_BOUND / QUERY_BOUND_RESULT / QUERY_BINDING_BY_OPENID / BINDING_RESULT（无调用链）
    }
}
