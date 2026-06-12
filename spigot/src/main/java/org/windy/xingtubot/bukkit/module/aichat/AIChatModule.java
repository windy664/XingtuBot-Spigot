package org.windy.xingtubot.bukkit.module.aichat;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.windy.xingtubot.bukkit.XingtuBot;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.ai.AiService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIChatModule implements Listener {

    private final FileConfiguration config;
    private final Logger logger;
    private final AIChatMemory memory = new AIChatMemory();
    private final AiService aiService;

    public AIChatModule(FileConfiguration config, Logger logger, AiService aiService) {
        this.config = config;
        this.logger = logger;
        this.aiService = aiService;
    }

    @EventHandler
    public void onGuildMessage(GuildMessageEvent event) {
        String msg = event.getMessage().trim();

        // 命中忽略前缀则不处理
        for (String prefix : config.getStringList("ignored-prefix")) {
            String regex = "^" + Pattern.quote(prefix.substring(1)) + ".*";
            Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(msg);
            if (matcher.find()) {
                XingtuBot.getInstance().log("匹配到忽略规则: " + regex);
                return;
            }
        }

        String key = event.getGuildId() + "#" + event.getFormId();
        List<Map<String, String>> messages = memory.getMessages(key);

        // 注入性格设定（system）
        String personality = config.getString(
                "personality",
                "你是一位温柔、体贴、爱说简单话的女朋友，总是安静地倾听，温柔地回应，回复不超过150字"
        ).trim();
        if (!personality.isEmpty()) {
            messages.add(createMessage("system", "你的性格设定：" + personality));
        }
        messages.add(createMessage("user", msg));

        // 异步调用 AI，避免阻塞主线程
        CompletableFuture.supplyAsync(() -> {
            try {
                XingtuBot.getInstance().log("向AI请求：" + messages);
                return aiService.chat(messages);
            } catch (Exception e) {
                logger.warning("AI 请求失败：" + e.getMessage());
                return null;
            }
        }).thenAccept(reply -> Bukkit.getScheduler().runTask(XingtuBot.getInstance(), () -> {
            if (reply != null) {
                event.reply(reply);
                messages.add(createMessage("assistant", reply));
                memory.setMessages(key, messages);
            } else {
                event.reply("AI 无法处理该请求。");
            }
        }));
    }

    private Map<String, String> createMessage(String role, String content) {
        Map<String, String> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }
}
