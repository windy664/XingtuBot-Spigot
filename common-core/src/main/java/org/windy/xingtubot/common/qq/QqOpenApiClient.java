package org.windy.xingtubot.common.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.util.Http;

import java.io.IOException;

/**
 * QQ 官方机器人 OpenAPI（主动调用 REST）客户端。
 *
 * <p>这是独立于 WebSocket 的“第二条通信路”：WebSocket / Webhook 负责<b>收</b>事件，
 * 本类负责<b>发</b>消息（被动回复为主）。全程出站 HTTPS，国内服务器可直连，无需备案。
 *
 * <p>用法：
 * <pre>
 *   QqOpenApiClient api = new QqOpenApiClient(appId, clientSecret);   // 正式环境
 *   api.sendGroupMessage(groupOpenid, "你好", msgId, 1);              // 被动回复群消息
 * </pre>
 *
 * <p>与 {@code AiService} 一致：请求走统一的 {@link Http}，兼容 Java 8，零额外依赖。
 */
public class QqOpenApiClient {

    /** 拿凭证（access_token）的固定地址，与接口域名不同。 */
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    /** 正式环境接口域名。 */
    public static final String API_PROD = "https://api.sgroup.qq.com";
    /** 沙箱环境接口域名。 */
    public static final String API_SANDBOX = "https://sandbox.api.sgroup.qq.com";

    // === 消息类型 msg_type ===
    public static final int MSG_TYPE_TEXT = 0;
    public static final int MSG_TYPE_MARKDOWN = 2;
    public static final int MSG_TYPE_ARK = 3;
    public static final int MSG_TYPE_EMBED = 4;
    public static final int MSG_TYPE_MEDIA = 7;

    // === 富媒体 file_type（QQ 官方：1图片 2视频 3语音 4文件）===
    public static final int FILE_IMAGE = 1;
    public static final int FILE_VIDEO = 2;
    public static final int FILE_VOICE = 3;
    public static final int FILE_FILE = 4;

    private final String appId;
    private final String clientSecret;
    private final String apiBase;
    private final BotLogger logger; // 可为 null

    // === access_token 缓存 ===
    private volatile String accessToken;
    private volatile long tokenExpireAt; // 毫秒时间戳，提前 60s 视为过期
    private final Object tokenLock = new Object();

    public QqOpenApiClient(String appId, String clientSecret) {
        this(appId, clientSecret, API_PROD, null);
    }

    public QqOpenApiClient(String appId, String clientSecret, boolean sandbox, BotLogger logger) {
        this(appId, clientSecret, sandbox ? API_SANDBOX : API_PROD, logger);
    }

    public QqOpenApiClient(String appId, String clientSecret, String apiBase, BotLogger logger) {
        this.appId = appId;
        this.clientSecret = clientSecret;
        this.apiBase = apiBase;
        this.logger = logger;
    }

    // ========================= 凭证管理 =========================

    /** 获取有效 access_token，过期时自动刷新（线程安全，双重检查）。 */
    public String getAccessToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return accessToken;
        }
        synchronized (tokenLock) {
            if (accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
                return accessToken;
            }
            refreshToken();
            return accessToken;
        }
    }

    private void refreshToken() throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("appId", appId);
        body.addProperty("clientSecret", clientSecret);

        Http.Response tokenResp = Http.post(TOKEN_URL).json(body.toString()).timeout(10000, 15000).send();
        String resp = exchangeResult(tokenResp);
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
        if (!json.has("access_token")) {
            throw new IOException("获取 access_token 失败: " + resp);
        }
        this.accessToken = json.get("access_token").getAsString();
        long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 7200L;
        // 提前 60 秒过期，避免临界点用到失效 token
        this.tokenExpireAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L;
        log("已刷新 access_token，有效期约 " + expiresIn + "s");
    }

    // ========================= 发送消息 =========================

    /**
     * 被动回复群消息。
     *
     * @param groupOpenid 事件里的 group_openid
     * @param content     文本内容
     * @param msgId       收到事件的消息 id（被动回复必填，5 分钟内有效）
     * @param msgSeq      同一 msgId 多条回复时需自增，避免被去重
     * @return 接口返回的 JSON 字符串
     */
    public String sendGroupMessage(String groupOpenid, String content, String msgId, int msgSeq) throws IOException {
        return post("/v2/groups/" + groupOpenid + "/messages", textBody(content, msgId, msgSeq));
    }

    /** 被动回复单聊（C2C）消息，参数含义同 {@link #sendGroupMessage}。 */
    public String sendC2CMessage(String userOpenid, String content, String msgId, int msgSeq) throws IOException {
        return post("/v2/users/" + userOpenid + "/messages", textBody(content, msgId, msgSeq));
    }

    /**
     * 通过 event_id 被动回复群事件（如新成员加入、机器人被加群等）。
     *
     * <p>与 {@link #sendGroupMessage} 的区别：用 {@code event_id} 而非 {@code msg_id}，
     * QQ 服务端根据 event_id 将消息关联到对应事件。事件类通知必须用此方法，用 msg_id 发不出来。
     *
     * @param groupOpenid 事件里的 group_openid
     * @param content     文本内容
     * @param eventId     事件 id（被动回复必填，5 分钟内有效）
     * @param msgSeq      自增序列号
     */
    public String sendGroupMessageByEvent(String groupOpenid, String content, String eventId, int msgSeq) throws IOException {
        return post("/v2/groups/" + groupOpenid + "/messages", textBodyByEvent(content, eventId, msgSeq));
    }

    /** 通过 event_id 被动回复单聊事件。 */
    public String sendC2CMessageByEvent(String userOpenid, String content, String eventId, int msgSeq) throws IOException {
        return post("/v2/users/" + userOpenid + "/messages", textBodyByEvent(content, eventId, msgSeq));
    }

    /**
     * 被动回复频道（guild）子频道消息。
     * 频道接口与群不同：用 channel_id，被动回复带 msg_id、无 msg_seq。
     */
    public String sendChannelMessage(String channelId, String content, String msgId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        if (msgId != null) {
            body.addProperty("msg_id", msgId);
        }
        return post("/channels/" + channelId + "/messages", body);
    }

    private JsonObject textBody(String content, String msgId, int msgSeq) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("msg_type", MSG_TYPE_TEXT);
        attachPassive(body, msgId, msgSeq);
        return body;
    }

    private JsonObject textBodyByEvent(String content, String eventId, int msgSeq) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("msg_type", MSG_TYPE_TEXT);
        attachPassiveByEvent(body, eventId, msgSeq);
        return body;
    }

    // ===================== 富媒体（图片/语音/视频） =====================

    /** 上传群富媒体，返回 file_info（发消息时用）。url 为可公网访问的素材地址。 */
    public String uploadGroupFile(String groupOpenid, int fileType, String url) throws IOException {
        return uploadFile("/v2/groups/" + groupOpenid + "/files", fileType, url);
    }

    /** 上传单聊富媒体，返回 file_info。 */
    public String uploadC2CFile(String userOpenid, int fileType, String url) throws IOException {
        return uploadFile("/v2/users/" + userOpenid + "/files", fileType, url);
    }

    private String uploadFile(String path, int fileType, String url) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("file_type", fileType);
        body.addProperty("url", url);
        body.addProperty("srv_send_msg", false); // 先拿 file_info，交由发消息接口真正发送
        String resp = post(path, body);
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
        if (!json.has("file_info")) {
            throw new IOException("上传富媒体失败: " + resp);
        }
        return json.get("file_info").getAsString();
    }

    // ===================== 富媒体 base64 直传（不依赖公网/SCF，实验性）=====================

    /** 用 base64 直传上传群富媒体，返回 file_info。data 为图片字节的 base64（不带 data: 前缀）。 */
    public String uploadGroupFileData(String groupOpenid, int fileType, String base64) throws IOException {
        return uploadFileData("/v2/groups/" + groupOpenid + "/files", fileType, base64);
    }

    /** 用 base64 直传上传单聊富媒体，返回 file_info。 */
    public String uploadC2CFileData(String userOpenid, int fileType, String base64) throws IOException {
        return uploadFileData("/v2/users/" + userOpenid + "/files", fileType, base64);
    }

    private String uploadFileData(String path, int fileType, String base64) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("file_type", fileType);
        body.addProperty("file_data", base64); // base64 二进制数据，绕开公网 url
        body.addProperty("srv_send_msg", false);
        String resp = post(path, body);
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
        if (!json.has("file_info")) {
            throw new IOException("base64 上传富媒体失败: " + resp);
        }
        return json.get("file_info").getAsString();
    }

    /** 便捷：用 base64 发群图片（生成图直传，不碰 SCF）。 */
    public String sendGroupImageData(String groupOpenid, byte[] imageBytes, String content,
                                     String msgId, int msgSeq) throws IOException {
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        String fileInfo = uploadGroupFileData(groupOpenid, FILE_IMAGE, base64);
        return sendGroupMedia(groupOpenid, fileInfo, content, msgId, msgSeq);
    }

    /** 发送群富媒体（已有 file_info）。 */
    public String sendGroupMedia(String groupOpenid, String fileInfo, String content, String msgId, int msgSeq) throws IOException {
        return post("/v2/groups/" + groupOpenid + "/messages", mediaBody(fileInfo, content, msgId, msgSeq));
    }

    /** 发送单聊富媒体（已有 file_info）。 */
    public String sendC2CMedia(String userOpenid, String fileInfo, String content, String msgId, int msgSeq) throws IOException {
        return post("/v2/users/" + userOpenid + "/messages", mediaBody(fileInfo, content, msgId, msgSeq));
    }

    /** 便捷：发群图片（自动上传 + 发送）。imageUrl 须可公网访问。 */
    public String sendGroupImage(String groupOpenid, String imageUrl, String content, String msgId, int msgSeq) throws IOException {
        String fileInfo = uploadGroupFile(groupOpenid, FILE_IMAGE, imageUrl);
        return sendGroupMedia(groupOpenid, fileInfo, content, msgId, msgSeq);
    }

    /** 便捷：发单聊图片（自动上传 + 发送）。 */
    public String sendC2CImage(String userOpenid, String imageUrl, String content, String msgId, int msgSeq) throws IOException {
        String fileInfo = uploadC2CFile(userOpenid, FILE_IMAGE, imageUrl);
        return sendC2CMedia(userOpenid, fileInfo, content, msgId, msgSeq);
    }

    /** 便捷：发群语音（URL 方式上传 + 发送）。voiceUrl 须公网可访问。 */
    public String sendGroupVoice(String groupOpenid, String voiceUrl, String msgId, int msgSeq) throws IOException {
        String fileInfo = uploadGroupFile(groupOpenid, FILE_VOICE, voiceUrl);
        return sendGroupMedia(groupOpenid, fileInfo, "", msgId, msgSeq);
    }

    /** 便捷：发群语音（base64 直传，不依赖公网）。audioBytes 为 mp3/wav/silk 等音频字节。 */
    public String sendGroupVoiceData(String groupOpenid, byte[] audioBytes, String msgId, int msgSeq) throws IOException {
        String base64 = java.util.Base64.getEncoder().encodeToString(audioBytes);
        String fileInfo = uploadGroupFileData(groupOpenid, FILE_VOICE, base64);
        return sendGroupMedia(groupOpenid, fileInfo, "", msgId, msgSeq);
    }

    /** 便捷：发单聊语音（base64 直传）。 */
    public String sendC2CVoiceData(String userOpenid, byte[] audioBytes, String msgId, int msgSeq) throws IOException {
        String base64 = java.util.Base64.getEncoder().encodeToString(audioBytes);
        String fileInfo = uploadC2CFileData(userOpenid, FILE_VOICE, base64);
        return sendC2CMedia(userOpenid, fileInfo, "", msgId, msgSeq);
    }

    /** 便捷：发群视频（自动上传 + 发送）。videoUrl 须 mp4、公网可访问。 */
    public String sendGroupVideo(String groupOpenid, String videoUrl, String content, String msgId, int msgSeq) throws IOException {
        String fileInfo = uploadGroupFile(groupOpenid, FILE_VIDEO, videoUrl);
        return sendGroupMedia(groupOpenid, fileInfo, content, msgId, msgSeq);
    }

    /** 便捷：发单聊语音（silk）。 */
    public String sendC2CVoice(String userOpenid, String voiceUrl, String msgId, int msgSeq) throws IOException {
        String fileInfo = uploadC2CFile(userOpenid, FILE_VOICE, voiceUrl);
        return sendC2CMedia(userOpenid, fileInfo, "", msgId, msgSeq);
    }

    /** 便捷：发单聊视频（mp4）。 */
    public String sendC2CVideo(String userOpenid, String videoUrl, String content, String msgId, int msgSeq) throws IOException {
        String fileInfo = uploadC2CFile(userOpenid, FILE_VIDEO, videoUrl);
        return sendC2CMedia(userOpenid, fileInfo, content, msgId, msgSeq);
    }

    private JsonObject mediaBody(String fileInfo, String content, String msgId, int msgSeq) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content == null ? "" : content);
        body.addProperty("msg_type", MSG_TYPE_MEDIA);
        JsonObject media = new JsonObject();
        media.addProperty("file_info", fileInfo);
        body.add("media", media);
        attachPassive(body, msgId, msgSeq);
        return body;
    }

    // ===================== 主动消息（不带 msg_id，无需 5 分钟窗口） =====================

    /**
     * 主动发送群消息（不依赖被动回复窗口）。
     *
     * <p><b>markdown-only：</b>产品定位所有出站消息统一走 markdown 通道。传入的纯文本会经
     * {@link org.windy.xingtubot.common.util.Md#plain(String)} 转义后以 markdown 形式发送，
     * 故调用方无需改动即自动 markdown 化（需机器人具备原生 markdown 权限 + 主动消息权限）。
     *
     * @param groupOpenid 目标群的 group_openid
     * @param content     文本内容（将被转义为 markdown）
     * @return 接口返回的 JSON 字符串
     */
    public String sendProactiveGroupMessage(String groupOpenid, String content) throws IOException {
        return sendProactiveGroupMarkdown(groupOpenid, org.windy.xingtubot.common.util.Md.plain(content));
    }

    /**
     * 主动发送群 Markdown 消息（不依赖被动回复窗口）。
     *
     * @param groupOpenid    目标群的 group_openid
     * @param markdownContent Markdown 内容
     * @return 接口返回的 JSON 字符串
     */
    public String sendProactiveGroupMarkdown(String groupOpenid, String markdownContent) throws IOException {
        return sendProactiveGroupMarkdown(groupOpenid, markdownContent, null);
    }

    /**
     * 主动发送群 Markdown，可附带<b>自定义内联按钮键盘</b>（不依赖被动回复窗口）。
     *
     * @param keyboardJson {@code Keyboards.callback(...)} 产出的键盘 JSON 字符串；为 null/空则不带键盘
     */
    public String sendProactiveGroupMarkdown(String groupOpenid, String markdownContent,
                                             String keyboardJson) throws IOException {
        JsonObject md = new JsonObject();
        md.addProperty("content", org.windy.xingtubot.common.util.Md.softBreaks(markdownContent));
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", MSG_TYPE_MARKDOWN);
        body.add("markdown", md);
        if (keyboardJson != null && !keyboardJson.trim().isEmpty()) {
            body.add("keyboard", JsonParser.parseString(keyboardJson).getAsJsonObject());
        }
        return post("/v2/groups/" + groupOpenid + "/messages", body);
    }

    /**
     * 主动发送单聊（C2C）文本消息（不依赖被动回复窗口）。
     *
     * @param userOpenid 目标用户的 openid
     * @param content    文本内容
     * @return 接口返回的 JSON 字符串
     */
    public String sendProactiveC2CMessage(String userOpenid, String content) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("msg_type", MSG_TYPE_TEXT);
        return post("/v2/users/" + userOpenid + "/messages", body);
    }

    // ===================== Markdown（可带按钮键盘） =====================

    /** 发送群 Markdown（原生 content；需机器人具备原生 markdown 权限）。 */
    public String sendGroupMarkdown(String groupOpenid, String markdownContent, String msgId, int msgSeq) throws IOException {
        return sendGroupMarkdown(groupOpenid, markdownContent, null, msgId, msgSeq);
    }

    /**
     * 发送群 Markdown，可附带按钮键盘模板。
     *
     * @param keyboardTemplateId 键盘模板 id（开放平台配置），为 null 则不带键盘
     */
    public String sendGroupMarkdown(String groupOpenid, String markdownContent, String keyboardTemplateId,
                                    String msgId, int msgSeq) throws IOException {
        return sendMarkdown("/v2/groups/" + groupOpenid + "/messages", markdownContent, keyboardTemplateId, msgId, msgSeq);
    }

    /** 发送单聊 Markdown（可带键盘模板）。 */
    public String sendC2CMarkdown(String userOpenid, String markdownContent, String keyboardTemplateId,
                                  String msgId, int msgSeq) throws IOException {
        return sendMarkdown("/v2/users/" + userOpenid + "/messages", markdownContent, keyboardTemplateId, msgId, msgSeq);
    }

    /** 通过 event_id 发送群 Markdown（用于回复事件类通知）。 */
    public String sendGroupMarkdownByEvent(String groupOpenid, String markdownContent, String eventId, int msgSeq) throws IOException {
        return sendMarkdownByEvent("/v2/groups/" + groupOpenid + "/messages", markdownContent, eventId, msgSeq);
    }

    /** 通过 event_id 发送单聊 Markdown。 */
    public String sendC2CMarkdownByEvent(String userOpenid, String markdownContent, String eventId, int msgSeq) throws IOException {
        return sendMarkdownByEvent("/v2/users/" + userOpenid + "/messages", markdownContent, eventId, msgSeq);
    }

    private String sendMarkdown(String path, String markdownContent, String keyboardTemplateId,
                               String msgId, int msgSeq) throws IOException {
        JsonObject md = new JsonObject();
        md.addProperty("content", markdownContent);
        JsonObject keyboard = null;
        if (keyboardTemplateId != null) {
            keyboard = new JsonObject();
            keyboard.addProperty("id", keyboardTemplateId);
        }
        return sendMarkdownRaw(path, md, keyboard, msgId, msgSeq);
    }

    private String sendMarkdownByEvent(String path, String markdownContent, String eventId, int msgSeq) throws IOException {
        JsonObject md = new JsonObject();
        md.addProperty("content", org.windy.xingtubot.common.util.Md.softBreaks(markdownContent));
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", MSG_TYPE_MARKDOWN);
        body.add("markdown", md);
        attachPassiveByEvent(body, eventId, msgSeq);
        return post(path, body);
    }

    /** 全自定义群 markdown / keyboard 对象，最大灵活度（模板参数、内联按钮等）。 */
    public String sendGroupMarkdownRaw(String groupOpenid, JsonObject markdown, JsonObject keyboard,
                                       String msgId, int msgSeq) throws IOException {
        return sendMarkdownRaw("/v2/groups/" + groupOpenid + "/messages", markdown, keyboard, msgId, msgSeq);
    }

    private String sendMarkdownRaw(String path, JsonObject markdown, JsonObject keyboard,
                                   String msgId, int msgSeq) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", MSG_TYPE_MARKDOWN);
        // content 字段群消息必填，用 markdown 的文本内容做兜底
        if (markdown != null && markdown.has("content")) {
            // 统一补软换行（单 \n 会被 QQ 吞成一行）；幂等，模板参数类 markdown 无 content 不受影响
            String normalized = org.windy.xingtubot.common.util.Md.softBreaks(markdown.get("content").getAsString());
            markdown.addProperty("content", normalized);
            body.addProperty("content", normalized);
            body.add("markdown", markdown);
        }
        if (keyboard != null) body.add("keyboard", keyboard);
        attachPassive(body, msgId, msgSeq);
        return post(path, body);
    }

    // ===================== Ark 卡片 =====================

    /** 发送群 Ark 卡片。ark 为按模板拼好的对象（template_id + kv）。 */
    public String sendGroupArk(String groupOpenid, JsonObject ark, String msgId, int msgSeq) throws IOException {
        return sendArk("/v2/groups/" + groupOpenid + "/messages", ark, msgId, msgSeq);
    }

    /** 发送单聊 Ark 卡片。 */
    public String sendC2CArk(String userOpenid, JsonObject ark, String msgId, int msgSeq) throws IOException {
        return sendArk("/v2/users/" + userOpenid + "/messages", ark, msgId, msgSeq);
    }

    private String sendArk(String path, JsonObject ark, String msgId, int msgSeq) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", MSG_TYPE_ARK);
        body.add("ark", ark);
        attachPassive(body, msgId, msgSeq);
        return post(path, body);
    }

    // ===================== Embed =====================
    // 注意：embed 主要面向频道场景，群/单聊支持情况以平台为准，属实验性。

    /** 发送群 Embed。embed 为已拼好的对象（title/prompt/thumbnail/fields 等）。 */
    public String sendGroupEmbed(String groupOpenid, JsonObject embed, String msgId, int msgSeq) throws IOException {
        return sendEmbed("/v2/groups/" + groupOpenid + "/messages", embed, msgId, msgSeq);
    }

    /** 发送单聊 Embed。 */
    public String sendC2CEmbed(String userOpenid, JsonObject embed, String msgId, int msgSeq) throws IOException {
        return sendEmbed("/v2/users/" + userOpenid + "/messages", embed, msgId, msgSeq);
    }

    private String sendEmbed(String path, JsonObject embed, String msgId, int msgSeq) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("msg_type", MSG_TYPE_EMBED);
        body.add("embed", embed);
        attachPassive(body, msgId, msgSeq);
        return post(path, body);
    }

    /** 统一注入被动回复字段（msg_id + 自增 msg_seq）。 */
    private void attachPassive(JsonObject body, String msgId, int msgSeq) {
        if (msgId != null) {
            body.addProperty("msg_id", msgId);
            body.addProperty("msg_seq", msgSeq);
        }
    }

    /** 统一注入被动回复字段（event_id + 自增 msg_seq），用于回复事件类通知。 */
    private void attachPassiveByEvent(JsonObject body, String eventId, int msgSeq) {
        if (eventId != null) {
            body.addProperty("event_id", eventId);
            body.addProperty("msg_seq", msgSeq);
        }
    }

    // ========================= 底层 HTTP =========================

    /**
     * 回应一次按钮交互（INTERACTION_CREATE）：{@code PUT /interactions/{id}}。
     * <b>必须回应</b>，否则点击按钮的客户端会一直处于 loading 状态直到超时。
     * 群聊/单聊/频道按钮交互通用（顶层接口，非频道作用域）。
     *
     * @param interactionId 事件里的 interaction id（d.id）
     * @param code          0=成功；其它为失败码（详见官方文档）
     */
    public String ackInteraction(String interactionId, int code) throws IOException {
        String token = getAccessToken();
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        Http.Response resp = Http.put(apiBase + "/interactions/" + interactionId)
                .json(body.toString())
                .header("Authorization", "QQBot " + token)
                .header("X-Union-Appid", appId)
                .timeout(10000, 30000)
                .send();
        return exchangeResult(resp);
    }

    private String post(String path, JsonObject body) throws IOException {
        String token = getAccessToken();
        Http.Response resp = Http.post(apiBase + path)
                .json(body.toString())
                .header("Authorization", "QQBot " + token)
                .header("X-Union-Appid", appId)
                .timeout(10000, 30000)
                .send();
        return exchangeResult(resp);
    }

    /** HTTP >= 400 时连同响应体抛出异常，否则返回响应体。 */
    private String exchangeResult(Http.Response resp) throws IOException {
        if (resp.code >= 400) {
            throw new IOException("OpenAPI 请求失败: " + resp.code + " " + resp.body);
        }
        return resp.body;
    }

    private void log(String msg) {
        if (logger != null) {
            logger.info("[OpenAPI] " + msg);
        }
    }

    public String getAppId() {
        return this.appId;
    }
}
