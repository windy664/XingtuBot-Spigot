package org.windy.xingtubot.common.binding;

/**
 * 绑定与登录的<b>契约（接口，平台无关）</b>。
 *
 * <p>本接口是 core 暴露给跨模块消费方的<b>端口</b>：代理桥（{@code VelocityBridge}/{@code BungeeCordBridge}
 * 处理 DECLARE_QQ 跨服协议）、三端 PAPI placeholder、其它附属（如 xt-chatlink 的 openid→玩家 查询）
 * 都经服务总线拿到本接口来调用，故<b>契约必须留在 core</b>（否则这些核心侧代码编不过）。
 *
 * <p><b>具体实现是白名单专属策略</b>（头像比对 / 待验证表 / 限流 / 成功卡片），落在 xt-auth 的
 * {@code org.windy.xingtubot.ext.xtauth.binding.BindingServiceImpl}，由 xt-auth 经
 * {@code registerService(BindingService.class, impl)} 注入服务总线。core 只依赖本接口，不依赖实现。
 *
 * <p>绑定流程（头像比对版）、appId 惰性解析等实现细节见 {@code BindingServiceImpl} 的类注释。
 */
public interface BindingService {

    /**
     * 设置群内成功回复的 markdown 卡片模板（占位符 {@code {player}}/{@code {qq}}）。
     * 传入空/空白则保留实现内置默认。对应 config: {@code messages.bind-success} / {@code messages.login-success}。
     */
    void setSuccessTemplates(String bindSuccess, String loginSuccess);

    /** 设置群里完成绑定的关键词（config: {@code binding-prompt}），用于游戏内提示文案。 */
    void setBindingPrompt(String bindingPrompt);

    /** 单 openid 失败匹配尝试上限（config: {@code bind-max-attempts}）。 */
    void setMaxBindAttempts(int maxBindAttempts);

    /** 绑定仓库（跨模块只读消费方经此查 openid↔玩家）。 */
    BindingRepository getStore();

    boolean isPlayerBound(String player);

    /** 玩家退出/取消时清理其待验证记录。 */
    void cancelPending(String player);

    /** 该玩家在本次代理会话内是否已登录过（用于切服免重登）。 */
    boolean isLoggedInSession(String player);

    /**
     * 标记玩家在本次代理会话内已登录。供代理大脑在「IP 绑定的自动登录」放行后调用，
     * 使其后续跨子服切换走免重登路径（与群内按钮登录 {@code loginByGroup} 内部置位等效）。
     */
    void markLoggedInSession(String player);

    /** 玩家彻底退出代理时清除其登录会话态（再次进服需重新登录或满足自动登录条件）。 */
    void clearSession(String player);

    boolean hasPending(String player);

    /**
     * 玩家游戏内声明 QQ 号，下载该 QQ 头像指纹存入待验证表。<b>会下载头像，请在异步线程调用。</b>
     * 头像取不到 / 默认纯色 / 占位小图时拒绝（无可用指纹则无法在群里完成头像比对）。
     */
    Result declareQQ(String player, String qq);

    /**
     * 群里发「绑定」触发：下载发送者 openid 的头像，与所有待验证记录比对，命中唯一匹配即绑定。
     * <b>会下载头像，请在异步线程调用。</b>
     */
    Result bindByAvatar(String openid);

    /** 群里发「登录」触发：查 openid 绑定的玩家，在线则免密码登录。返回消息回复群。 */
    Result loginByGroup(String openid);

    /** 清理过期的待验证记录。 */
    void clearExpired();

    /** 操作结果：是否成功 + 错误码 + 用户消息。 */
    class Result {
        public final boolean success;
        public final String message;   // 给用户看的（不含技术细节）
        public final String code;      // 内部错误码（给开发者看）
        public final boolean markdown; // message 是否为预渲染 markdown 卡片（true → 发送方走 replyMarkdown，不转义）

        private Result(boolean success, String message, String code, boolean markdown) {
            this.success = success;
            this.message = message;
            this.code = code;
            this.markdown = markdown;
        }

        public static Result ok(String msg) {
            return new Result(true, msg, null, false);
        }

        /** 成功，且 message 是 markdown 卡片（发送方应走 replyMarkdown）。 */
        public static Result okMarkdown(String md) {
            return new Result(true, md, null, true);
        }

        public static Result fail(String userMsg, String code) {
            return new Result(false, userMsg, code, false);
        }

        /** 后台日志用：翻译错误码为技术描述。 */
        public String describe() {
            if (code == null) return success ? "OK" : "UNKNOWN";
            switch (code) {
                case "QQ_FORMAT":          return "QQ号格式不正确";
                case "QQ_AVATAR_FAIL":     return "取不到声明QQ号的头像";
                case "QQ_AVATAR_UNUSABLE": return "声明QQ号的头像不可用（默认/纯色/占位）";
                case "NO_PENDING":         return "没有待绑定记录";
                case "APPID_EMPTY":        return "openapi-app-id 为空（核心未就绪/未配置）";
                case "AVATAR_FETCH_FAIL":  return "取不到 openid 头像";
                case "AVATAR_PLACEHOLDER": return "openid 头像为占位图（疑似 appId 配错）";
                case "AVATAR_LOW_INFO":    return "openid 头像为默认/纯色头像";
                case "NO_MATCH":           return "无头像匹配的待绑定记录";
                case "AVATAR_AMBIGUOUS":   return "多条记录头像相似，存在歧义";
                case "TOO_MANY_ATTEMPTS":  return "失败匹配次数超限";
                case "NOT_BOUND":          return "未绑定";
                case "AUTH_UNAVAILABLE":   return "无 AuthAdapter，无法驱动登录";
                case "PLAYER_OFFLINE":     return "玩家不在线";
                case "ALREADY_BOUND":      return "QQ已绑定";
                default:                   return code;
            }
        }
    }
}
