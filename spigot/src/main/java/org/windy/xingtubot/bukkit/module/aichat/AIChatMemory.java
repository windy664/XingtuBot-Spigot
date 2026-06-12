package org.windy.xingtubot.bukkit.module.aichat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话短期记忆：每个会话保留最近 20 条消息。
 */
public class AIChatMemory {
    private final Map<String, List<Map<String, String>>> memory = new HashMap<>();

    public List<Map<String, String>> getMessages(String key) {
        return new ArrayList<>(memory.getOrDefault(key, new ArrayList<>()));
    }

    public void setMessages(String key, List<Map<String, String>> messages) {
        memory.put(key, messages.size() > 20
                ? messages.subList(messages.size() - 20, messages.size())
                : messages);
    }
}
