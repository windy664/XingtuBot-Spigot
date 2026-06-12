package org.windy.xingtubot.common.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一的 AI 调用实现（OpenAI 兼容接口，默认 DeepSeek）。
 * 同时支持单轮与多轮（含 system / 历史）对话。
 */
public class AiService {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient client = new OkHttpClient();

    public AiService(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, "deepseek-chat");
    }

    public AiService(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /** 单轮对话 */
    public String chat(String userMessage) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", userMessage);
        messages.add(user);
        return chat(messages);
    }

    /** 多轮对话，messages 每项包含 role / content */
    public String chat(List<Map<String, String>> messages) throws Exception {
        String url = baseUrl + "/chat/completions";

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
