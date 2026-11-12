package org.windy.xingtubot.common.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.util.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 唯一的 AI 调用实现（OpenAI 兼容接口）。
 * 走统一的 {@link Http}，零额外依赖、兼容 Java 8。
 * 同时支持单轮与多轮（含 system / 历史）对话。
 * 支持动态切换模型（如文字用 mimo-v2.5，图片用 mimo-v2-omni）。
 *
 * <h3>调用参数</h3>
 * <ul>
 *   <li>{@code temperature}：默认 0.9，略高于保守值，让群聊回复不至于千篇一律。</li>
 *   <li>{@code maxTokens}：默认 0 = <b>不发送该字段</b>（不同 provider 对 max_tokens /
 *       max_completion_tokens 的支持不一致，不发最保险）。配成 &gt;0 可限制输出长度、防小作文；
 *       若 provider 报 400 并点名 max_tokens，会自动去掉该字段重试一次。</li>
 *   <li>超时：连接 5s / 读取 25s（聊天场景等 60 秒没有意义）。</li>
 *   <li>重试：默认 1 次，仅对网络错误与 5xx 生效；401/403/400 直接抛出不重试。</li>
 * </ul>
 *
 * <h3>错误语义</h3>
 * 任何失败（HTTP 错误码、choices 为空、content 为空、网络异常）都抛 {@link IOException}，
 * 调用方据此走兜底路径。<b>不会再返回 "AI没有返回内容" 这类占位串</b>——那会把机器故障
 * 原样发进群里。
 */
public class AiService {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int retry;

    public AiService(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, "mimo-v2.5");
    }

    public AiService(String apiKey, String baseUrl, String model) {
        this(apiKey, baseUrl, model, 0.9, 0, 5000, 25000, 1);
    }

    public AiService(String apiKey, String baseUrl, String model,
                     double temperature, int maxTokens,
                     int connectTimeoutMs, int readTimeoutMs, int retry) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.retry = retry;
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
     *
     * @param overrideModel 为 null 时使用默认模型，否则用指定模型。
     *                      例如传 "mimo-v2-omni" 处理含图片的消息。
     */
    public String chat(List<Map<String, String>> messages, String overrideModel) throws IOException {
        String useModel = overrideModel != null ? overrideModel : model;
        JsonObject payload = buildPayload(messages, useModel);

        IOException last = null;
        int attempts = Math.max(0, retry) + 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(Math.min(2000L, 300L * attempt));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                return doChat(payload);
            } catch (IOException e) {
                // provider 不认 max_tokens → 去掉该字段再试一次
                if (payload.has("max_tokens") && mentionsMaxTokens(e.getMessage())) {
                    payload.remove("max_tokens");
                    last = e;
                    continue;
                }
                if (!isRetryable(e)) throw e;
                last = e;
            }
        }
        throw last != null ? last : new IOException("AI 请求失败");
    }

    // ==================== 内部实现 ====================

    private JsonObject buildPayload(List<Map<String, String>> messages, String useModel) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", useModel);
        payload.addProperty("stream", false);
        payload.addProperty("temperature", temperature);
        if (maxTokens > 0) {
            payload.addProperty("max_tokens", maxTokens);
        }

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
        return payload;
    }

    private String doChat(JsonObject payload) throws IOException {
        Http.Response resp = Http.post(baseUrl + "/chat/completions")
                .json(payload.toString())
                .header("Authorization", "Bearer " + apiKey)
                .timeout(connectTimeoutMs, readTimeoutMs)
                .send();

        if (resp.code >= 400) {
            String body = truncate(resp.body);
            // 鉴权/参数错误重试没有意义，直接抛
            if (resp.code == 400 || resp.code == 401 || resp.code == 403) {
                throw new IOException("API请求失败(不重试): " + resp.code + " " + body);
            }
            throw new IOException("API请求失败: " + resp.code + " " + body);
        }

        JsonObject result;
        try {
            JsonElement parsed = JsonParser.parseString(resp.body);
            if (!parsed.isJsonObject()) {
                throw new IOException("API 返回的不是 JSON 对象");
            }
            result = parsed.getAsJsonObject();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("API 返回解析失败: " + e.getMessage());
        }

        JsonArray choices = result.has("choices") && result.get("choices").isJsonArray()
                ? result.getAsJsonArray("choices") : null;
        if (choices == null || choices.size() == 0) {
            throw new IOException("API 未返回内容(choices 为空)");
        }

        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = first.has("message") && first.get("message").isJsonObject()
                ? first.getAsJsonObject("message") : null;
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new IOException("API 返回内容为空");
        }
        String content = message.get("content").getAsString();
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("API 返回内容为空");
        }
        return content;
    }

    /** 5xx 与网络类错误可重试；4xx 不重试。 */
    private static boolean isRetryable(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        if (msg.startsWith("API请求失败(不重试)")) return false;
        return true;
    }

    private static boolean mentionsMaxTokens(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("max_tokens") || lower.contains("max tokens")
                || lower.contains("max_completion_tokens");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
