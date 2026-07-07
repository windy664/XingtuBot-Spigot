package org.windy.xingtubot.common.poll;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenID → 昵称双层缓存（L1 内存 + L2 DB）。
 *
 * <p>架构：
 * <ul>
 *   <li>L1：ConcurrentHashMap，所有读走这里，零开销</li>
 *   <li>L2：SQLite/MySQL，启动时暖缓存，写入时异步落盘</li>
 * </ul>
 *
 * <p>写入策略：写穿 L1，异步延迟写 L2（30 秒去重，避免高频群消息刷 DB）。
 * 读取策略：L1 miss 时查 DB 并回填 L1。
 */
public class OpenidNameCache {

    private static final OpenidNameCache INSTANCE = new OpenidNameCache();

    /** 匹配 QQ @提及格式：<@openid> */
    private static final Pattern MENTION_PATTERN = Pattern.compile("<@([^>]+)>");

    /** L1 内存缓存 */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /** L2 DB 仓库（可 null = 纯内存模式） */
    private volatile OpenidNameRepository repository;

    /** 延迟写入调度器 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OpenidName-Writer");
        t.setDaemon(true);
        return t;
    });

    /** 待写入 DB 的脏数据（去重用） */
    private final ConcurrentHashMap<String, String> dirty = new ConcurrentHashMap<>();

    private volatile boolean initialized = false;

    private OpenidNameCache() {}

    public static OpenidNameCache getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化：注入 DB 仓库并暖缓存。应在插件启动时调用。
     * 不调用则退化为纯内存模式。
     */
    public void init(OpenidNameRepository repository) {
        this.repository = repository;
        // 从 DB 加载暖缓存
        Map<String, String> loaded = repository.loadAll();
        cache.putAll(loaded);
        initialized = true;

        // 定时刷脏数据到 DB（每 30 秒）
        scheduler.scheduleWithFixedDelay(this::flushDirty, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 缓存一条 openid→昵称 映射（写穿 L1，异步写 L2）。
     */
    public void put(String openid, String nickname) {
        if (openid == null || openid.isEmpty() || nickname == null || nickname.isEmpty()) return;

        String old = cache.put(openid, nickname);
        // 只在值变化时标记脏
        if (!nickname.equals(old)) {
            dirty.put(openid, nickname);
        }
    }

    /**
     * 读取昵称。init() 已暖加载全量，put() 写穿 L1，这里只走 L1。
     */
    public String get(String openid) {
        return openid == null ? null : cache.get(openid);
    }

    /**
     * 解析消息内容中的 {@code <@openid>} 占位符，替换为缓存的昵称。
     * 未命中缓存的 openid 替换为 "群成员"。
     */
    public String resolveMentions(String content) {
        if (content == null || content.isEmpty()) return content;

        Matcher matcher = MENTION_PATTERN.matcher(content);
        if (!matcher.find()) return content; // 快速路径：无 @提及

        StringBuffer sb = new StringBuffer();
        matcher.reset();
        while (matcher.find()) {
            String openid = matcher.group(1);
            String name = get(openid);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("@" + (name != null ? name : "群成员")));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 手动刷新脏数据到 DB（shutdown 时调用）。 */
    public void flushDirty() {
        if (dirty.isEmpty()) return;

        // 取出当前脏批次并清空
        ConcurrentHashMap<String, String> batch = new ConcurrentHashMap<>(dirty);
        dirty.keySet().removeAll(batch.keySet());

        OpenidNameRepository repo = repository;
        if (repo == null) return;

        for (Map.Entry<String, String> e : batch.entrySet()) {
            try {
                repo.upsert(e.getKey(), e.getValue());
            } catch (Exception ex) {
                // 写入失败放回脏队列下次重试
                dirty.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    /** 关闭时刷盘。 */
    public void shutdown() {
        flushDirty();
        scheduler.shutdownNow();
    }

    public int size() {
        return cache.size();
    }
}
