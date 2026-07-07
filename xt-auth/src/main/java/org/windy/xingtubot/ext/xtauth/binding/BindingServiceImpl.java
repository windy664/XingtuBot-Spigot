package org.windy.xingtubot.ext.xtauth.binding;

import org.windy.xingtubot.common.binding.AuthAdapter;
import org.windy.xingtubot.common.binding.BindingEntry;
import org.windy.xingtubot.common.binding.BindingRepository;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.binding.BindingService.Result;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 白名单绑定与登录的<b>实现</b>（auth 专属策略；契约接口 {@link BindingService} 在 core）。
 *
 * <p><b>注册绑定流程（头像比对版）：</b>
 * <ol>
 *   <li>玩家游戏内声明 QQ 号 → {@link #declareQQ}：下载<b>该 QQ 号的头像</b>
 *       ({@code q.qlogo.cn/headimg_dl?dst_uin=<QQ>}) 算 dHash 指纹，存入待验证表。
 *       头像取不到 / 是默认纯色 / 是占位小图时直接拒绝（无可用指纹无法比对）；</li>
 *   <li>玩家在群里发送「绑定」→ {@link #bindByAvatar}：下载<b>发送者 openid 的头像</b>
 *       ({@code q.qlogo.cn/qqapp/<appId>/<openid>}) 算指纹，与所有待验证记录比对，
 *       找到唯一头像匹配的玩家即绑定（openid ↔ 玩家），并 forceRegister。</li>
 * </ol>
 *
 * <p><b>为什么改回头像比对：</b>这两条链接对同一个人返回的是<b>同一张源图</b>
 * （实测 dHash 汉明距离 0），据此可把「群里发消息的 openid」关联到「游戏里声明的 QQ 号」，
 * 玩家无需手抄验证码，体验更顺。<b>权衡：</b>QQ 头像是公开的，理论上可被人下载后改成同图
 * 顶替；如需更强证明请改用一次性验证码方案。
 *
 * <p><b>appId 惰性解析：</b>openid 头像 URL 强依赖 openapi-app-id，但它要等核心 bot 起好才填进
 * {@code XingtuBotService}（尤其 Velocity，setApiClient 在 init 后段才发生）。故不在构造时一次性
 * 捕获——那样若彼时为空就永久固化、之后所有绑定都回落成 40×40 企鹅占位图。改为每次 bindByAvatar
 * 经 {@code appIdSupplier} 现取，玩家发「绑定」时核心早已就绪。
 *
 * <p>登录流程：玩家群里发「登录」(带 openid) → {@link #loginByGroup}：查绑定玩家，在线则 forceLogin。
 */
public class BindingServiceImpl implements BindingService {

    private static final Pattern QQ_PATTERN = Pattern.compile("^[1-9][0-9]{4,14}$");

    /** 头像歧义保护：次优匹配与最优匹配的距离差小于此值时视为歧义，拒绝绑定（防相似头像误绑）。 */
    private static final int AMBIGUITY_MARGIN = 4;

    private final BindingRepository store;
    private final AuthAdapter auth;
    private final Supplier<String> appIdSupplier;
    private final Consumer<String> logger;
    private final long pendingTtlMillis;
    private final int avatarThreshold;

    // 单个 openid 在限流窗口内允许的最大失败匹配次数。
    private volatile int maxBindAttempts = 5;
    private static final long ATTEMPT_WINDOW_MILLIS = 10 * 60 * 1000L;
    // 群里完成绑定的关键词（来自 config: binding-prompt），仅用于拼提示文案。
    private volatile String bindingPrompt = "绑定";

    // 待验证表：player（小写）-> 记录
    private final ConcurrentHashMap<String, PendingBinding> pending = new ConcurrentHashMap<>();
    // 失败匹配限流：openid -> 尝试计数（防有人反复发「绑定」碰运气）
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    // 本次代理会话已登录的玩家（小写名）。用于跨子服切换免重复登录；玩家彻底退出代理时清除。
    // 注意：IP 绑定的「自动登录」信任期由【代理大脑】(VelocityBridge) 持有（它才有玩家 IP），本服务只管当前会话态。
    private final java.util.Set<String> loggedIn = ConcurrentHashMap.newKeySet();

    public BindingServiceImpl(BindingRepository store, AuthAdapter auth, Supplier<String> appIdSupplier,
                              Consumer<String> logger) {
        this(store, auth, appIdSupplier, logger, 5 * 60 * 1000L, AvatarMatcher.DEFAULT_THRESHOLD);
    }

    public BindingServiceImpl(BindingRepository store, AuthAdapter auth, Supplier<String> appIdSupplier,
                              Consumer<String> logger, long pendingTtlMillis, int avatarThreshold) {
        this.store = store;
        this.auth = auth;
        this.appIdSupplier = appIdSupplier != null ? appIdSupplier : () -> "";
        this.logger = logger;
        this.pendingTtlMillis = pendingTtlMillis;
        this.avatarThreshold = avatarThreshold;
    }

    /** 现取 appId（惰性，非构造时捕获）；归一化为非 null、去空白。 */
    private String appId() {
        String v = appIdSupplier.get();
        return v == null ? "" : v.trim();
    }

    // 群内成功回复卡片模板（markdown，占位符 {player}/{qq}）；config 可覆盖，留空用内置默认。
    private volatile String bindSuccessTpl = DEFAULT_BIND_SUCCESS;
    private volatile String loginSuccessTpl = DEFAULT_LOGIN_SUCCESS;

    /** 默认绑定成功卡片：热情一点，给玩家好心情。 */
    private static final String DEFAULT_BIND_SUCCESS =
            "## 🎉 绑定成功 · 欢迎加入！\n"
            + "👤 **玩家**　{player}\n"
            + "🐧 **QQ**　　{qq}\n"
            + "\n> 🌟 白名单已开通，进服开启你的冒险吧~";

    /** 默认登录成功卡片。 */
    private static final String DEFAULT_LOGIN_SUCCESS =
            "## ✅ 登录成功 · 欢迎回来！\n"
            + "👤 **玩家**　{player}\n"
            + "\n> 🎮 一切就绪，祝你玩得开心！";

    @Override
    public void setSuccessTemplates(String bindSuccess, String loginSuccess) {
        if (bindSuccess != null && !bindSuccess.trim().isEmpty()) this.bindSuccessTpl = bindSuccess;
        if (loginSuccess != null && !loginSuccess.trim().isEmpty()) this.loginSuccessTpl = loginSuccess;
    }

    /** 占位符替换：{player}/{qq}。 */
    private static String render(String tpl, String player, String qq) {
        return tpl.replace("{player}", player == null ? "" : player)
                .replace("{qq}", qq == null ? "" : qq);
    }

    @Override
    public void setBindingPrompt(String bindingPrompt) {
        if (bindingPrompt != null && !bindingPrompt.trim().isEmpty()) this.bindingPrompt = bindingPrompt.trim();
    }

    @Override
    public void setMaxBindAttempts(int maxBindAttempts) {
        if (maxBindAttempts > 0) this.maxBindAttempts = maxBindAttempts;
    }

    @Override
    public BindingRepository getStore() {
        return store;
    }

    @Override
    public boolean isPlayerBound(String player) {
        return store.isPlayerBound(player);
    }

    @Override
    public void cancelPending(String player) {
        pending.remove(player.toLowerCase());
    }

    @Override
    public boolean isLoggedInSession(String player) {
        return loggedIn.contains(player.toLowerCase());
    }

    @Override
    public void markLoggedInSession(String player) {
        loggedIn.add(player.toLowerCase());
    }

    @Override
    public void clearSession(String player) {
        loggedIn.remove(player.toLowerCase());
    }

    @Override
    public boolean hasPending(String player) {
        return pending.containsKey(player.toLowerCase());
    }

    @Override
    public Result declareQQ(String player, String qq) {
        if (!QQ_PATTERN.matcher(qq).matches()) {
            return Result.fail("§cQQ号格式不正确，请输入 5~15 位数字", "QQ_FORMAT");
        }
        if (store.isPlayerBound(player)) {
            return Result.fail("§e你已绑定过白名单，无需重复绑定", "ALREADY_BOUND");
        }

        AvatarMatcher.Fingerprint fp;
        try {
            fp = AvatarMatcher.fingerprintFromUrl(qqAvatarUrl(qq));
        } catch (Exception e) {
            log("[QQ_FETCH_FAIL] qq=" + qq + ": " + e.getMessage());
            return Result.fail("§c取不到该QQ号的头像，请确认QQ号填写正确、且该QQ已设置头像后重试", "QQ_AVATAR_FAIL");
        }
        if (!fp.isUsable()) {
            log("[QQ_AVATAR_UNUSABLE] qq=" + qq + " size=" + fp.srcWidth + "x" + fp.srcHeight
                    + " variance=" + (long) fp.variance);
            return Result.fail("§c该QQ头像是默认/纯色或无法识别，无法用于绑定。请先在QQ设置一张清晰头像后再绑定", "QQ_AVATAR_UNUSABLE");
        }

        pending.put(player.toLowerCase(), new PendingBinding(player, qq, fp.hash));
        return Result.ok("§a已记录QQ §f" + qq + "§a。\n§a请在群里发送「§e§l" + bindingPrompt
                + "§a」完成白名单绑定（5分钟内有效）");
    }

    @Override
    public Result bindByAvatar(String openid) {
        clearExpired();

        if (store.findByOpenid(openid) != null) {
            return Result.fail("⚠️ 您的QQ已绑定过白名单", "ALREADY_BOUND");
        }
        if (isThrottled(openid)) {
            return Result.fail("⚠️ 尝试次数过多，请稍后再试", "TOO_MANY_ATTEMPTS");
        }
        if (pending.isEmpty()) {
            return Result.fail("⚠️ 当前没有待绑定的记录，请先在游戏内输入你的 QQ 号", "NO_PENDING");
        }

        // appId 现取（惰性）：为空说明核心 bot 还没起好或没配 openapi-app-id；直接给配置错而非去拉企鹅。
        String appId = appId();
        if (appId.isEmpty()) {
            log("[APPID_EMPTY] openid=" + openid + " —— 取到的 openapi-app-id 为空（核心 bot 未就绪或未配置），无法构造 openid 头像 URL");
            return Result.fail("⚠️ 绑定服务尚未就绪（openapi-app-id 为空），请稍候重试或联系管理员", "APPID_EMPTY");
        }

        AvatarMatcher.Fingerprint fp;
        try {
            fp = AvatarMatcher.fingerprintFromUrl(openidAvatarUrl(openid));
        } catch (Exception e) {
            log("[OPENID_FETCH_FAIL] openid=" + openid + ": " + e.getMessage());
            return Result.fail("⚠️ 取不到你的QQ头像，暂时无法绑定，请稍后再试", "AVATAR_FETCH_FAIL");
        }
        if (fp.isTooSmall()) {
            // appId 非空但仍回落成 40×40 企鹅占位图 → appId 配错（与机器人实际 AppID 不符）。
            log("[OPENID_PLACEHOLDER] openid=" + openid + " size=" + fp.srcWidth + "x" + fp.srcHeight
                    + " —— openid 头像回落成占位图，appId 可能配错（当前 appId=" + appId + "）");
            return Result.fail("⚠️ 绑定服务未正确配置（管理员请检查 openapi-app-id 是否与机器人一致），暂时无法绑定", "AVATAR_PLACEHOLDER");
        }
        if (fp.isLowInfo()) {
            return Result.fail("⚠️ 你的QQ头像是默认/纯色头像，无法用于绑定，请先在QQ设置一张清晰头像后重试", "AVATAR_LOW_INFO");
        }

        // 在所有待验证记录里找头像最接近的（同时记录次优，用于歧义保护）。
        PendingBinding best = null;
        int bestDist = Integer.MAX_VALUE, secondDist = Integer.MAX_VALUE;
        for (PendingBinding p : pending.values()) {
            int d = AvatarMatcher.hammingDistance(p.qqAvatarHash, fp.hash);
            if (d < bestDist) {
                secondDist = bestDist;
                bestDist = d;
                best = p;
            } else if (d < secondDist) {
                secondDist = d;
            }
        }

        if (best == null || bestDist > avatarThreshold) {
            recordAttempt(openid);
            log("[NO_AVATAR_MATCH] openid=" + openid + " bestDist=" + bestDist + " pending=" + pending.size());
            return Result.fail("⚠️ 没找到与你头像匹配的待绑定记录。请确认：游戏内输入的是【你本人】的QQ号，"
                    + "且与你现在的QQ头像一致", "NO_MATCH");
        }
        // 歧义保护：次优也落在阈值内且与最优过近 → 无法确认是哪条，拒绝以防误绑。
        if (secondDist <= avatarThreshold && (secondDist - bestDist) < AMBIGUITY_MARGIN) {
            recordAttempt(openid);
            log("[AVATAR_AMBIGUOUS] openid=" + openid + " best=" + bestDist + " second=" + secondDist);
            return Result.fail("⚠️ 有多条待绑定记录头像过于相似，无法自动确认，请联系管理员手动绑定", "AVATAR_AMBIGUOUS");
        }

        PendingBinding p = best;
        boolean online = auth != null && auth.isOnline(p.player);
        store.put(new BindingEntry(p.player, openid, p.qq));
        pending.remove(p.player.toLowerCase());
        attempts.remove(openid);
        log("[BIND_OK] player=" + p.player + " openid=" + openid + " dist=" + bestDist);

        if (online) {
            auth.register(p.player);
            // 绑定成功即视为本会话已登录：退出时才会被武装自动登录信任期（同设备重进免密）。
            // 否则首次绑定后退出再进还要再发一次「登录」——标记会话让绑定直接衔接免密体验。
            loggedIn.add(p.player.toLowerCase());
            auth.titlePlayer(p.player, "§a§l✅ 绑定成功", "§f欢迎加入，祝游戏愉快");
            auth.messagePlayer(p.player, "§a✅ 绑定成功！已为你注册，可以正常游玩了");
            return Result.okMarkdown(render(bindSuccessTpl, p.player, p.qq));
        }
        // 玩家此刻不在线（绑定前掉线/切服）：绑定已写入持久化，待其重新进服后发「登录」解锁。
        log("[BIND_OFFLINE] player=" + p.player + " openid=" + openid + " 已绑定，待重新进服登录");
        return Result.okMarkdown(render(bindSuccessTpl, p.player, p.qq)
                + "\n> ⏳ 你当前不在线，请重新进服后在群里发送「登录」开始游玩");
    }

    @Override
    public Result loginByGroup(String openid) {
        BindingEntry e = store.findByOpenid(openid);
        if (e == null) {
            return Result.fail("⚠️ 您未绑定白名单，请先完成绑定", "NOT_BOUND");
        }
        if (auth == null) {
            // 仅注册了绑定库、未接认证适配器的部署（如大脑只存数据不驱动登录）。
            log("[AUTH_UNAVAILABLE] loginByGroup 无 AuthAdapter，无法驱动登录 player=" + e.player);
            return Result.fail("⚠️ 登录服务暂不可用，请稍后再试或联系管理员", "AUTH_UNAVAILABLE");
        }
        if (!auth.isOnline(e.player)) {
            return Result.fail("⚠️ 玩家 " + e.player + " 当前不在线，请先进入服务器再登录", "PLAYER_OFFLINE");
        }
        // 原子去重：Set.add 仅首次返回 true。并发/重复点登录时只有第一次真正登录+反馈，
        // 其余返回 ALREADY_LOGGED_IN（按钮路径据此静默，不刷屏）。退出代理 clearSession 后可再次登录。
        if (!loggedIn.add(e.player.toLowerCase())) {
            return Result.fail("ℹ️ 你已经登录过了，无需重复登录", "ALREADY_LOGGED_IN");
        }
        auth.login(e.player);
        auth.titlePlayer(e.player, "§a§l✅ 登录成功", "§f祝你游戏愉快");
        auth.messagePlayer(e.player, "§a✅ 你已通过QQ群登录，祝游戏愉快！");
        return Result.okMarkdown(render(loginSuccessTpl, e.player, null));
    }

    @Override
    public void clearExpired() {
        pending.values().removeIf(p -> p.isExpired(pendingTtlMillis));
        attempts.values().removeIf(a -> System.currentTimeMillis() - a.windowStart > ATTEMPT_WINDOW_MILLIS);
    }

    // ---------------- 限流 ----------------

    private boolean isThrottled(String openid) {
        Attempt a = attempts.get(openid);
        if (a == null) return false;
        if (System.currentTimeMillis() - a.windowStart > ATTEMPT_WINDOW_MILLIS) {
            attempts.remove(openid);
            return false;
        }
        return a.count >= maxBindAttempts;
    }

    private void recordAttempt(String openid) {
        attempts.compute(openid, (k, a) -> {
            long now = System.currentTimeMillis();
            if (a == null || now - a.windowStart > ATTEMPT_WINDOW_MILLIS) {
                Attempt fresh = new Attempt();
                fresh.windowStart = now;
                fresh.count = 1;
                return fresh;
            }
            a.count++;
            return a;
        });
    }

    private static final class Attempt {
        int count;
        long windowStart;
    }

    private String qqAvatarUrl(String qq) {
        return "https://q.qlogo.cn/headimg_dl?dst_uin=" + qq + "&spec=640";
    }

    private String openidAvatarUrl(String openid) {
        return "https://q.qlogo.cn/qqapp/" + appId() + "/" + openid + "/640";
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }
}
