package org.windy.xingtubot.common.onebot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OneBot 11 HTTP API 客户端。
 *
 * <p>封装主动调用 OneBot 后端的 REST API（事件推送走 WS，API 调用走 HTTP）。
 * 与 QQ 官方 {@code QqOpenApiClient} 平行，但鉴权方式不同（Bearer token vs QQBot token）。
 *
 * <p>所有请求统一的 Bearer 鉴权，无需换 token 流程。
 */
public final class OneBotApiClient {

    private final String apiBase;
    private final String accessToken;
    private final Gson gson;
    private final BotLogger logger;

    // 自身 QQ 号（从事件首帧捕获后设置）
    private volatile String selfId;

    public OneBotApiClient(String apiBase, String accessToken, Gson gson, BotLogger logger) {
        // 确保末尾无斜杠
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.accessToken = accessToken;
        this.gson = gson;
        this.logger = logger;
    }

    public void setSelfId(String selfId) {
        this.selfId = selfId;
    }

    public String getSelfId() {
        return selfId;
    }

    // ==================== 主动消息 ====================

    /**
     * 主动发送群消息。
     *
     * @param groupId 群号（字符串形式）
     * @param message 消息段列表
     * @return API 响应 JSON
     */
    public ApiResponse<ApiResponse.MessageInfo> sendGroupMessage(String groupId, List<MsgSegment> message) throws IOException {
        return post("/send_group_msg", groupMsgBody(groupId, message));
    }

    /**
     * 主动发送私聊消息。
     *
     * @param userId  用户 QQ 号（字符串形式）
     * @param message 消息段列表
     */
    public ApiResponse<ApiResponse.MessageInfo> sendPrivateMessage(String userId, List<MsgSegment> message) throws IOException {
        return post("/send_private_msg", privateMsgBody(userId, message));
    }

    /**
     * 发送通用消息（自动判断群聊/私聊）。
     *
     * @param messageType "group" 或 "private"
     * @param id          群号或用户 QQ 号（字符串形式）
     * @param message     消息段列表
     */
    public ApiResponse<ApiResponse.MessageInfo> sendMessage(String messageType, String id, List<MsgSegment> message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("message_type", messageType);
        if ("group".equals(messageType)) {
            // 使用 Gson 将字符串数字序列化为数值
            body.addProperty("group_id", Long.parseLong(id));
        } else {
            body.addProperty("user_id", Long.parseLong(id));
        }
        body.add("message", segmentsToJsonArray(message));
        body.addProperty("auto_escape", false);
        return post("/send_msg", body);
    }

    // ==================== 信息查询 ====================

    /**
     * 获取群信息。
     *
     * @param groupId 群号（字符串形式）
     */
    public ApiResponse<ApiResponse.GroupInfo> getGroupInfo(String groupId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("group_id", Long.parseLong(groupId));
        return get("/get_group_info", params);
    }

    /**
     * 获取用户信息。
     *
     * @param userId 用户 QQ 号（字符串形式）
     */
    public ApiResponse<ApiResponse.UserInfo> getStrangerInfo(String userId) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("user_id", Long.parseLong(userId));
        return get("/get_stranger_info", params);
    }

    // ==================== 按钮交互 ACK（可选）====================

    /**
     * 回应按钮交互（部分后端支持）。
     * ACK 失败不得阻塞主流程——仅 warn 日志。
     */
    public void ackInteraction(String interactionId) {
        try {
            // OB11 的 ACK 机制取决于后端实现，大多数后端无此接口
            // 这里预留接口，调用失败仅 warn
            log("[WARN] ackInteraction 在当前 OneBot 11 后端下可能不支持（interactionId=" + interactionId + "）");
        } catch (Exception e) {
            log("[WARN] ackInteraction 失败（忽略）: " + e.getMessage());
        }
    }

    // ==================== 内部 HTTP ====================

    private ApiResponse<ApiResponse.MessageInfo> post(String path, JsonObject body) throws IOException {
        String resp = executeHttp("POST", path, body.toString());
        return parseMessageResponse(resp);
    }

    @SuppressWarnings("unchecked")
    private <T> ApiResponse<T> get(String path, JsonObject params) throws IOException {
        String queryString = params != null ? "?" + paramsToQuery(params) : "";
        String resp = executeHttp("GET", path + queryString, null);
        return parseResponse(resp);
    }

    private String executeHttp(String method, String path, String jsonBody) throws IOException {
        URL url = new URL(apiBase + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setDoInput(true);
        conn.setDoOutput(jsonBody != null);

        if (jsonBody != null) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        String responseBody;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            responseBody = sb.toString();
        } catch (Exception e) {
            throw new IOException("HTTP " + method + " " + path + " 失败: " + e.getMessage());
        }

        if (code >= 400) {
            throw new IOException("OneBot API 返回错误 " + code + ": " + responseBody);
        }

        conn.disconnect();
        return responseBody;
    }

    // ==================== 响应解析 ====================

    private ApiResponse<ApiResponse.MessageInfo> parseMessageResponse(String json) throws IOException {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        int retCode = obj.has("retcode") ? obj.get("retcode").getAsInt() : 0;
        if (retCode != 0) {
            String msg = obj.has("msg") ? obj.get("msg").getAsString() : "未知错误";
            return ApiResponse.failure(retCode, msg);
        }
        if (obj.has("data") && obj.get("data").isJsonObject()) {
            JsonObject data = obj.getAsJsonObject("data");
            long msgId = data.has("message_id") ? data.get("message_id").getAsLong() : 0;
            long time = data.has("time") ? data.get("time").getAsLong() : 0;
            return ApiResponse.success(new ApiResponse.MessageInfo(msgId, time));
        }
        return ApiResponse.success(null);
    }

    @SuppressWarnings("unchecked")
    private <T> ApiResponse<T> parseResponse(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        int retCode = obj.has("retcode") ? obj.get("retcode").getAsInt() : 0;
        if (retCode != 0) {
            String msg = obj.has("msg") ? obj.get("msg").getAsString() : "未知错误";
            return ApiResponse.failure(retCode, msg);
        }
        return (ApiResponse<T>) ApiResponse.success(obj.get("data"));
    }

    // ==================== 载荷构造 ====================

    private JsonObject groupMsgBody(String groupId, List<MsgSegment> message) {
        JsonObject body = new JsonObject();
        body.addProperty("group_id", Long.parseLong(groupId));
        body.add("message", segmentsToJsonArray(message));
        body.addProperty("auto_escape", false);
        return body;
    }

    private JsonObject privateMsgBody(String userId, List<MsgSegment> message) {
        JsonObject body = new JsonObject();
        body.addProperty("user_id", Long.parseLong(userId));
        body.add("message", segmentsToJsonArray(message));
        body.addProperty("auto_escape", false);
        return body;
    }

    private JsonArray segmentsToJsonArray(List<MsgSegment> segments) {
        JsonArray arr = new JsonArray();
        if (segments == null) return arr;
        for (MsgSegment seg : segments) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", seg.type());
            obj.add("data", seg.data());
            arr.add(obj);
        }
        return arr;
    }

    private String paramsToQuery(JsonObject params) {
        StringBuilder sb = new StringBuilder();
        for (String key : params.keySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(key).append("=").append(params.get(key).getAsString());
        }
        return sb.toString();
    }

    private void log(String msg) {
        if (logger != null) {
            logger.info("[OneBotAPI] " + msg);
        }
    }
}
