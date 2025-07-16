package org.windy.xingtubot.core.ai;

import okhttp3.*;
import com.google.gson.*;
import java.util.*;

public class AiService {
    private final String apiKey;
    private final String baseUrl;
    private final OkHttpClient client = new OkHttpClient();

    public AiService(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public String chat(String userMessage) throws Exception {
        String url = baseUrl + "/chat/completions";
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "deepseek-chat");
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);
        payload.add("messages", messages);
        payload.addProperty("stream", false);

        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.parse("application/json")
        );
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new Exception("API请求失败: " + resp.code());
            String respStr = resp.body().string();
            // 解析AI回复
            JsonObject respJson = JsonParser.parseString(respStr).getAsJsonObject();
            JsonArray choices = respJson.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject msgObj = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                return msgObj.get("content").getAsString();
            }
            return "AI没有返回内容";
        }
    }
}