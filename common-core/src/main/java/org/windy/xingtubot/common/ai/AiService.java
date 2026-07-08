package org.windy.xingtubot.common.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.util.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一的 AI 调用实现（OpenAI 兼容接口，默认 DeepSeek）。
 * 走统一的 {@link Http}，零额外依赖、兼容 Java 8。
 * 同时支持单轮与多轮（含 system / 历史）对话。
 * 支持动态切换模型（如文字用 mimo-v2.5，图片用 mimo-v2-omni）。
 */
public class AiService {
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public AiService(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, "mimo-v2.5");
    }

    public AiService(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /** 获取默认模型名 */
    public String getDefaultModel() {
        return model;
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

    /** 多轮对话（使用默认模型） */
    public String chat(List<Map<String, String>> messages) throws IOException {
        return chat(messages, null);
    }

    /**
     * 多轮对话，支持动态指定模型。
     * @param overrideModel 为 null 时使用默认模型，否则用指定模型。
     *                      例如传 "mimo-v2-omni" 处理含图片的消息。
     */
    public String chat(List<Map<String, String>> messages, String overrideModel) throws IOException {
        String useModel = overrideModel != null ? overrideModel : model;

        JsonObject payload = new JsonObject();
        payload.addProperty("model", useModel);
        payload.addProperty("stream", false);

        JsonArray msgArray = new JsonArray();
        for (Map<String, String> m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.get("role"));

            String content = m.get("content");
            String imageUrl = m.get("image_url");

            // 多模态：有图片时用 content array 格式
            if (imageUrl != null && !imageUrl.isEmpty()) {
                JsonArray contentArray = new JsonArray();

                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", content != null ? content : "");
                contentArray.add(textPart);

                JsonObject imagePart = new JsonObject();
                imagePart.addProperty("type", "image_url");
                JsonObject imageUrlObj = new JsonObject();
                imageUrlObj.addProperty("url", imageUrl);
                imagePart.add("image_url", imageUrlObj);
                contentArray.add(imagePart);

                o.add("content", contentArray);
            } else {
                o.addProperty("content", content);
            }

            msgArray.add(o);
        }
        payload.add("messages", msgArray);

        Http.Response resp = Http.post(baseUrl + "/chat/completions")
                .json(payload.toString())
                .header("Authorization", "Bearer " + apiKey)
                .timeout(10000, 60000)
                .send();

        if (resp.code >= 400) {
            throw new IOException("API请求失败: " + resp.code + " " + resp.body);
        }

        JsonObject result = JsonParser.parseString(resp.body).getAsJsonObject();
        JsonArray choices = result.getAsJsonArray("choices");
        if (choices != null && choices.size() > 0) {
            return choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        }
        return "AI没有返回内容";
    }
}
