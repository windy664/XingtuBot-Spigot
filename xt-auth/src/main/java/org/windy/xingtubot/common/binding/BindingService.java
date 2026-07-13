package org.windy.xingtubot.common.binding;

/**
 * 绑定与登录的<b>契约（接口，平台无关）</b>。
 *
 * <p>本接口是 xt-auth 暴露给跨模块消费方的<b>端口</b>：代理桥、三端 PAPI placeholder、
 * 其它附属都经服务总线拿到本接口来调用。
 *
 * <p><b>具体实现</b>落在 {@code org.windy.xingtubot.ext.xtauth.binding.BindingServiceImpl}，
 * 由 xt-auth 经 {@code registerService(BindingService.class, impl)} 注入服务总线。
 */
public interface BindingService {

    void setSuccessTemplates(String bindSuccess, String loginSuccess);

    void setBindingPrompt(String bindingPrompt);

    void setMaxBindAttempts(int maxBindAttempts);

    void setGroupNumber(String groupNumber);

    BindingRepository getStore();

    boolean isPlayerBound(String player);

    void cancelPending(String player);

    boolean isLoggedInSession(String player);

    void markLoggedInSession(String player);

    void clearSession(String player);

    boolean hasPending(String player);

    Result declareQQ(String player, String qq);

    Result bindByAvatar(String openid);

    Result loginByGroup(String openid);

    void clearExpired();

    class Result {
        public final boolean success;
        public final String message;
        public final String code;
        public final boolean markdown;

        private Result(boolean success, String message, String code, boolean markdown) {
            this.success = success;
            this.message = message;
            this.code = code;
            this.markdown = markdown;
        }

        public static Result ok(String msg) {
            return new Result(true, msg, null, false);
        }

        public static Result okMarkdown(String md) {
            return new Result(true, md, null, true);
        }

        public static Result fail(String userMsg, String code) {
            return new Result(false, userMsg, code, false);
        }

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
