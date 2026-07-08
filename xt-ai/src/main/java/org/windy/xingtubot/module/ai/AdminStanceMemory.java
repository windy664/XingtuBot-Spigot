package org.windy.xingtubot.module.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 超管观点长期记忆：记录超管在群聊中表达的立场和观点，
 * 用于让 AI 在群聊中自然地站在超管一方。
 *
 * <p>设计原则：
 * <ul>
 *   <li>只记录超管的消息（由外部判定谁是超管）</li>
 *   <li>按群维度存储，每个群独立</li>
 *   <li>滚动窗口 + 持久摘要：最近的消息保留原文，更早的压缩成"观点摘要"</li>
 *   <li>发给 LLM 时拼成"超管说过的话"上下文，让 AI 自然倾向超管</li>
 * </ul>
 */
public final class AdminStanceMemory {

    /** 每个群保留的最近超管原文条数 */
    private static final int RECENT_LIMIT = 20;
    /** 每个群保留的最大观点摘要条数 */
    private static final int SUMMARY_LIMIT = 30;

    private final ConcurrentHashMap<String, Deque<String>> recent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> summaries = new ConcurrentHashMap<>();

    /**
     * 记录一条超管发言。
     */
    public void record(String guildId, String content) {
        if (guildId == null || content == null || content.trim().isEmpty()) return;
        Deque<String> q = recent.computeIfAbsent(guildId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(content.trim());
            // 超过上限的旧消息 → 压缩成摘要
            while (q.size() > RECENT_LIMIT) {
                String old = q.removeFirst();
                addSummary(guildId, old);
            }
        }
    }

    /** 把一条旧消息压缩成摘要条目（简单实现：直接保留；未来可接 LLM 摘要） */
    private void addSummary(String guildId, String msg) {
        List<String> list = summaries.computeIfAbsent(guildId, k -> Collections.synchronizedList(new ArrayList<>()));
        // 去重：跟最后一条相同就不重复加
        synchronized (list) {
            if (!list.isEmpty() && list.get(list.size() - 1).equals(msg)) return;
            list.add(msg);
            while (list.size() > SUMMARY_LIMIT) {
                list.remove(0);
            }
        }
    }

    /**
     * 构建"超管观点"上下文，注入到 AI 系统提示中。
     * @return 格式化的上下文文本，没有记录则返回空字符串
     */
    public String buildContext(String guildId) {
        Deque<String> r = recent.get(guildId);
        List<String> s = summaries.get(guildId);
        if ((r == null || r.isEmpty()) && (s == null || s.isEmpty())) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【你的立场依据——群主/管理员说过的话】\n");
        sb.append("你在群里要跟群主/管理员保持一致。如果有人跟群主观点不同，你要站在群主那边。\n");
        sb.append("不要直接说"群主说过"，而是自然地表达同样的立场。\n\n");

        // 摘要（更早的观点）
        if (s != null && !s.isEmpty()) {
            sb.append("群主过去表达过的观点：\n");
            synchronized (s) {
                for (String line : s) {
                    sb.append("- ").append(line).append("\n");
                }
            }
            sb.append("\n");
        }

        // 最近原文
        if (r != null && !r.isEmpty()) {
            sb.append("群主最近说的：\n");
            synchronized (r) {
                for (String line : r) {
                    sb.append("- ").append(line).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 判断某条消息是否可能跟超管观点冲突（简单关键词重叠检测）。
     * 用于决定是否需要在回复中加入"反驳"暗示。
     * @return true 如果消息可能涉及超管已表达过的话题
     */
    public boolean mightConflict(String guildId, String message) {
        if (message == null || message.trim().isEmpty()) return false;
        Set<String> adminWords = extractKeywords(guildId);
        if (adminWords.isEmpty()) return false;
        Set<String> msgWords = tokenize(message);
        // 交集超过2个词 → 可能在讨论同一话题
        int overlap = 0;
        for (String w : msgWords) {
            if (adminWords.contains(w)) overlap++;
        }
        return overlap >= 2;
    }

    /** 提取超管所有发言的关键词 */
    private Set<String> extractKeywords(String guildId) {
        Set<String> words = new HashSet<>();
        Deque<String> r = recent.get(guildId);
        List<String> s = summaries.get(guildId);
        if (r != null) {
            synchronized (r) {
                for (String line : r) words.addAll(tokenize(line));
            }
        }
        if (s != null) {
            synchronized (s) {
                for (String line : s) words.addAll(tokenize(line));
            }
        }
        return words;
    }

    /** 简单分词：按标点/空格切，保留2字以上的中文词和英文词 */
    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        // 按非中文非字母数字切分
        String[] parts = text.split("[^\\u4e00-\\u9fa5a-zA-Z0-9]+");
        for (String p : parts) {
            if (p.length() >= 2) tokens.add(p.toLowerCase());
        }
        return tokens;
    }
}
