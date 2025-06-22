package org.windy.xingtubot.bukkit.module.aichat;


import java.util.*;

public class AIChatMemory {
    private final Map<String, List<Map<String, String>>> memory = new HashMap<>();

    public List<Map<String, String>> getMessages(String key) {
        return new ArrayList<>(memory.getOrDefault(key, new ArrayList<>()));
    }

    public void setMessages(String key, List<Map<String, String>> messages) {
        memory.put(key, messages.size() > 20 ? messages.subList(messages.size() - 20, messages.size()) : messages);
    }
}
