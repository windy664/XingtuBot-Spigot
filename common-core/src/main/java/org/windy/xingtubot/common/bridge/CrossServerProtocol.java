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
        /** 玩家加入子服，上报给大脑判断绑定状态（未绑定则大脑回提示）。字段：player。 */
        PLAYER_JOIN,
        /** 玩家在游戏内声明了 QQ 号，上报给大脑算头像、存 pending。字段：player, qq。 */
        DECLARE_QQ,
        /** 玩家退出，清理其 pending。字段：player。 */
        PLAYER_QUIT,

        // ===== Velocity → Spigot（大脑派活给手脚）=====
        /** 代理回应握手：机器人由我（代理）主导，子服请当手脚。无额外字段。 */
        I_AM_BOSS,
        /** 让子服把某玩家标记为「等待输入QQ号」（未绑定时）。字段：player。 */
        NEED_QQ,
        /** 让子服清除某玩家的「等待输入QQ号」状态（已声明/绑定）。字段：player。 */
        CLEAR_QQ,
        /** 让子服对某玩家执行免密码注册（绑定成功后）。字段：player。 */
        DO_REGISTER,
        /** 让子服对某玩家执行强制登录。字段：player。 */
        DO_LOGIN,
        /** 给某在线玩家发一条游戏内消息。字段：player, message。 */
        MSG_PLAYER,

        // ===== 数据查询（SQLite 模式下子服向大脑查绑定）=====
        /** 子服查询：某玩家是否已绑定。字段：requestId, player。 */
        QUERY_BOUND,
        /** 大脑回应查询。字段：requestId, "1"/"0"。 */
        QUERY_BOUND_RESULT,

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
        SERVER_REGISTRY,

        // ===== 绑定数据跨服查询 =====
        /** 子服向大脑查询：某 openid 绑定了哪个玩家。字段：requestId, openid。 */
        QUERY_BINDING_BY_OPENID,
        /** 大脑回传绑定查询结果。字段：requestId, playerName（空=未绑定）。 */
        BINDING_RESULT
    }
}
