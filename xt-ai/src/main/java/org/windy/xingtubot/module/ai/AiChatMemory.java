package org.windy.xingtubot.module.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 对话短期记忆：每个会话保留最近 20 条消息。
 *
 * <p>用 {@link ConcurrentHashMap} + {@link CopyOnWriteArrayList}：AI 的处理在
 * 多线程池里并发跑，普通 HashMap 会有竞态与丢更新。
 */
public class AiChatMemory {
    private final Map<String, List<Map<String, String>>> memory = new ConcurrentHashMap<>();

    public List<Map<String, String>> getMessages(String key) {
        List<Map<String, String>> list = memory.get(key);
        if (list == null) return new ArrayList<>();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public void setMessages(String key, List<Map<String, String>> messages) {
        List<Map<String, String>> trimmed = messages.size() > 20
                ? new ArrayList<>(messages.subList(messages.size() - 20, messages.size()))
                : new ArrayList<>(messages);
        memory.put(key, trimmed);
    }
}
