package org.windy.xingtubot.module.aichat;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.windy.xingtubot.event.GuildMessageEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class AIChatModule implements Listener {

    private final String apiKey;
    private final FileConfiguration config;
    private final AIChatMemory memory = new AIChatMemory();
    private final AIChatKnowledgeBase kb;
    private final Logger logger;

    public AIChatModule(FileConfiguration config, Logger logger, String apiKey) {
        this.config = config;
        this.logger = logger;
        this.apiKey = apiKey;
        this.kb = new AIChatKnowledgeBase(config);
    }

    @EventHandler
    public void onGuildMessage(GuildMessageEvent event) {
        String msg = event.getMessage().trim();
        List<String> ignoredPrefixes = config.getStringList("ignored-prefix");

        for (String prefix : ignoredPrefixes) {
            if (msg.matches(prefix)) return;
        }

        String guildId = event.getGuildId();
        String formId = event.getFormId();
        String key = guildId + "#" + formId;

        // 知识库匹配
        String kbContext = kb.matchRelatedContent(msg);
        List<Map<String, String>> messages = memory.getMessages(key);

        // 从配置读取自定义性格
        String personality = config.getString("personality", "你是一位温柔、体贴、爱说简单话的女朋友，总是安静地倾听，温柔地回应，回复不超过150字").trim();

        // 添加 system 信息，包括知识库和自定义性格
        StringBuilder systemContent = new StringBuilder();
        if (!kbContext.isEmpty()) {
            systemContent.append("以下是相关资料：\n").append(kbContext);
        }
        if (!personality.isEmpty()) {
            if (systemContent.length() > 0) systemContent.append("\n\n");
            systemContent.append("你的性格设定：").append(personality);
        }
        if (systemContent.length() > 0) {
            messages.add(createMessage("system", systemContent.toString()));
        }

        messages.add(createMessage("user", msg));

        // 异步调用AI接口，防止主线程卡死
        CompletableFuture.supplyAsync(() -> {
            try {
                return callAI(messages);
            } catch (Exception e) {
                logger.warning("AI 请求失败：" + e.getMessage());
                return null;
            }
        }).thenAccept(reply -> {
            if (reply != null) {
                // Bukkit主线程回复消息
                Bukkit.getScheduler().runTask(org.windy.xingtubot.XingtuBot.getInstance(), () -> {
                    event.reply(reply);
                    messages.add(createMessage("assistant", reply));
                    memory.setMessages(key, messages);
                });
            } else {
                Bukkit.getScheduler().runTask(org.windy.xingtubot.XingtuBot.getInstance(), () -> {
                    event.reply("AI 无法处理该请求。");
                });
            }
        });
    }

    private String callAI(List<Map<String, String>> messages) throws IOException {
        URL url = new URL("https://api.deepseek.com/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        JsonObject json = new JsonObject();
        json.addProperty("model", "deepseek-chat");
        json.addProperty("stream", false);

        JsonArray msgArray = new JsonArray();
        for (Map<String, String> m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.get("role"));
            o.addProperty("content", m.get("content"));
            msgArray.add(o);
        }
        json.add("messages", msgArray);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] in = json.toString().getBytes(StandardCharsets.UTF_8);
            os.write(in);
        }

        StringBuilder response = new StringBuilder();
        try (Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8")) {
            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
            }
        }


        JsonObject result = JsonParser.parseString(response.toString()).getAsJsonObject();
        JsonArray choices = result.getAsJsonArray("choices");
        return choices.get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }

    private Map<String, String> createMessage(String role, String content) {
        Map<String, String> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }
}
