package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.config.BotConfig;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 触发护栏（纯检查，无副作用）：四个名单的热加载、全局每小时调用预算、
 * 单用户滑动窗口限频。防刷核心在 matches() 阶段拦截——命中黑名单或超限时
 * LLM API 根本不会被调。
 *
 * <h3>预算口径</h3>
 * <ul>
 *   <li>{@code ai-max-calls-per-hour}：<b>总 LLM 请求数</b>（judge + 生成都算），硬上限。</li>
 *   <li>{@code ai-max-judge-calls-per-hour}：judge 单独上限，0 = 不限（仍受总预算约束）。</li>
 *   <li>{@code ai-max-replies-per-hour}：正式回复条数上限，0 = 不限。</li>
 * </ul>
 */
final class AiGuards {

    // 命令关键词黑名单（不区分大小写，包含匹配）
    private volatile Set<String> blockedKeywords = Collections.emptySet();
    // 群聊白名单（为空则不限制，所有群都启用）
    private volatile Set<String> groupWhitelist = Collections.emptySet();
    // 攻击名单（用户ID → 说话直接攻击）
    private volatile Set<String> attackTargets = Collections.emptySet();
    // 用户黑名单（用户ID → 永远不触发AI）
    private volatile Set<String> blacklistUsers = Collections.emptySet();

    // 频率限制：单用户滑动窗口
    private final ConcurrentHashMap<String, Deque<Long>> rateLimitMap = new ConcurrentHashMap<>();
    // 全局每小时调用计数（judge 与正式生成都计入）
    private final AtomicInteger hourlyCalls = new AtomicInteger(0);
    // 全局每小时 judge（要不要插话）调用计数
    private final AtomicInteger hourlyJudgeCalls = new AtomicInteger(0);
    // 全局每小时正式回复条数（不统计 judge）
    private final AtomicInteger hourlyReplies = new AtomicInteger(0);
    /**
     * 当前小时窗口的起点。<b>必须在构造时就初始化</b>：
     * 旧实现留 0，第一次判定窗口时 `now - 0 > 1h` 成立，会把已累计的计数清零，
     * 导致「先不限额跑一阵、后来改成限额」时额度统计被抹掉。
     */
    private volatile long hourStart = System.currentTimeMillis();

    private final BotConfig config;

    AiGuards(BotConfig config) {
        this.config = config;
        reload();
    }

    /** 从配置重载四个名单（构造时调用；换配置后可手动调）。 */
    void reload() {
        this.blockedKeywords = loadBlockedKeywords(config);
        this.groupWhitelist = loadList(config, "group-whitelist");
        this.attackTargets = loadList(config, "attack-targets");
        this.blacklistUsers = loadList(config, "blacklist-users");
    }

    /** 群是否在白名单内（白名单为空 → 全部允许）。 */
    boolean isGroupAllowed(String guildId) {
        if (groupWhitelist.isEmpty()) return true;
        return guildId != null && groupWhitelist.contains(guildId);
    }

    /** 消息包含任何黑名单词 → true（应拦截）。不区分大小写，包含匹配。 */
    boolean isBlockedCommand(String msg) {
        if (blockedKeywords.isEmpty()) return false;
        String lower = msg.toLowerCase();
        for (String kw : blockedKeywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    boolean isAttackTarget(String senderId) {
        return senderId != null && attackTargets.contains(senderId);
    }

    boolean isBlacklisted(String senderId) {
        return senderId != null && blacklistUsers.contains(senderId);
    }

    /**
     * 小时总预算是否还有余量（<b>只看不占</b>）。
     * 用于 matches() 阶段的快速闸门：超了直接返回 false，连线程池槽位都不占。
     * 真正的占用由 {@link #tryReserveCall()} 在发请求前原子完成。
     */
    boolean budgetLeft() {
        int maxPerHour = config.getInt("ai-max-calls-per-hour", 60);
        rollHour();
        if (maxPerHour <= 0) return true; // 0 = 不限
        return hourlyCalls.get() < maxPerHour;
    }

    /**
     * 原子地占用一次 LLM 请求额度（硬上限）。
     *
     * <p><b>先占额度再发请求</b>——这样并发下也不会超发。judge 与正式生成都调用它，
     * 所以 {@code ai-max-calls-per-hour} 是<b>总请求数</b>：
     * 一次自主插话会消耗 2 次（judge 一次 + 生成一次）。
     *
     * @return true 额度已占用，可以发请求；false 已超限
     */
    boolean tryReserveCall() {
        int maxPerHour = config.getInt("ai-max-calls-per-hour", 60);
        rollHour();   // 先滚动窗口再占额度，避免跨小时时把计数清掉
        if (maxPerHour <= 0) { // 0 = 不限
            hourlyCalls.incrementAndGet();
            return true;
        }
        while (true) {
            int cur = hourlyCalls.get();
            if (cur >= maxPerHour) return false;
            if (hourlyCalls.compareAndSet(cur, cur + 1)) return true;
        }
    }

    /**
     * judge（要不要插话）的单独上限是否还有余量。只判断不占用——
     * 真正的占用由 {@link #tryReserveCall()} 统一负责，避免两套额度互相打架。
     */
    boolean judgeQuotaLeft() {
        int maxJudge = config.getInt("ai-max-judge-calls-per-hour", 0);
        if (maxJudge <= 0) return true;
        rollHour();
        return hourlyJudgeCalls.get() < maxJudge;
    }

    /** 正式回复条数的单独上限是否还有余量。只判断不占用。 */
    boolean replyQuotaLeft() {
        int maxReplies = config.getInt("ai-max-replies-per-hour", 0);
        if (maxReplies <= 0) return true;
        rollHour();
        return hourlyReplies.get() < maxReplies;
    }

    /** 小时窗口滚动 + 计数重置。 */
    private void rollHour() {
        long now = System.currentTimeMillis();
        if (now - hourStart > 3600_000L) {
            synchronized (this) {
                if (now - hourStart > 3600_000L) {
                    hourlyCalls.set(0);
                    hourlyJudgeCalls.set(0);
                    hourlyReplies.set(0);
                    hourStart = now;
                }
            }
        }
    }

    /** 记一次 judge 请求（请求已发出后调用）。 */
    void recordJudgeCall() {
        hourlyJudgeCalls.incrementAndGet();
    }

    /** 记一次正式回复（请求已发出后调用）。 */
    void recordReplyCall() {
        hourlyReplies.incrementAndGet();
    }

    /** 本小时已用的 LLM 请求总数（judge + 生成），用于启动日志与诊断。 */
    int callsThisHour() {
        rollHour();
        return hourlyCalls.get();
    }

    /**
     * 单用户滑动窗口限频。
     * @param isDirectAt @触发时阈值更高（1分钟10次），自主参与更严（1分钟3次）
     */
    boolean checkRateLimit(String key, boolean isDirectAt) {
        long now = System.currentTimeMillis();
        long windowMs = 60_000L;
        int maxHits = isDirectAt ? 10 : 3;

        Deque<Long> timestamps = rateLimitMap.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxHits) return false;
            timestamps.addLast(now);
            return true;
        }
    }

    // ---- 名单加载 ----

    private static Set<String> loadBlockedKeywords(BotConfig config) {
        Set<String> set = new HashSet<>();
        for (String kw : config.getStringList("blocked-keywords")) {
            if (kw != null && !kw.isEmpty()) {
                set.add(kw.toLowerCase());
            }
        }
        // 硬编码兜底：这些永远不该触发AI
        set.add("登录");
        set.add("绑定");
        set.add("注册");
        set.add("白名单");
        return set;
    }

    private static Set<String> loadList(BotConfig config, String key) {
        Set<String> set = new HashSet<>();
        for (String id : config.getStringList(key)) {
            if (id != null && !id.trim().isEmpty()) {
                set.add(id.trim());
            }
        }
        return set;
    }
}
