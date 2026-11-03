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
 * LLM API 根本不会被调，handle() 里的 recordCall 只是兜底计数。
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
    // 全局每小时调用计数
    private final AtomicInteger hourlyCalls = new AtomicInteger(0);
    private volatile long hourStart = 0;

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
     * 全局每小时调用上限是否还有余量。
     * @return true 在预算内，false 超限
     */
    boolean checkHourlyBudget() {
        int maxPerHour = config.getInt("ai-max-calls-per-hour", 60);
        if (maxPerHour <= 0) return true; // 0 = 不限

        long now = System.currentTimeMillis();
        if (now - hourStart > 3600_000L) {
            synchronized (this) {
                if (now - hourStart > 3600_000L) {
                    hourlyCalls.set(0);
                    hourStart = now;
                }
            }
        }
        return hourlyCalls.get() < maxPerHour;
    }

    /** 记一次 API 调用（handle 真正发请求前的兜底计数）。 */
    void recordCall() {
        hourlyCalls.incrementAndGet();
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
