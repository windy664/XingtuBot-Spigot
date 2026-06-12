package org.windy.xingtubot.common.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 唯一的 AI 调用实现（OpenAI 兼容接口，默认 DeepSeek）。
 * 使用 JDK 自带 HttpURLConnection，零额外依赖、兼容 Java 8。
 * 同时支持单轮与多轮（含 system / 历史）对话。
 */
public class AiService {
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public AiService(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, "deepseek-chat");
    }

    public AiService(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /** 单轮对话 */
    public String chat(String userMessage) throws IOException {
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", userMessage);
        messages.add(user);
        return chat(messages);
    }

    /** 多轮对话，messages 每项包含 role / content */
    public String chat(List<Map<String, String>> messages) throws IOException {
        URL url = new URL(baseUrl + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);

        JsonArray msgArray = new JsonArray();
        for (Map<String, String> m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.get("role"));
            o.addProperty("content", m.get("content"));
            msgArray.add(o);
        }
        payload.add("messages", msgArray);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder response = new StringBuilder();
        try (InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
             Scanner scanner = new Scanner(in, "UTF-8")) {
            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
            }
        }

        if (conn.getResponseCode() >= 400) {
            throw new IOException("API请求失败: " + conn.getResponseCode() + " " + response);
        }

        JsonObject result = JsonParser.parseString(response.toString()).getAsJsonObject();
        JsonArray choices = result.getAsJsonArray("choices");
        if (choices != null && choices.size() > 0) {
            return choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        }
        return "AI没有返回内容";
    }
}
