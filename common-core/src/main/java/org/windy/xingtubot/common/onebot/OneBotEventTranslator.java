package org.windy.xingtubot.common.onebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.event.BotReplier;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.util.AssertUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * OneBot 11 事件翻译器：将 OB11 JSON 事件翻译为平台无关的 {@link BotMessageEvent}。
 *
 * <p>核心职责：
 * <ul>
 *   <li>解析消息段数组 → {@code message} + {@code imageUrls} + {@code botMentioned}</li>
 *   <li>翻译 {@code group_id}/{@code user_id} → 全字符串 ID</li>
 *   <li>注入 {@code messagenger.toSenderUid()} 映射 {@code senderUid}</li>
 *   <li>入站去重（LRU 1024）</li>
 * </ul>
 *
 * <p>不处理 CQ 码字符串（文档 5.1 决策 —— 本次只支持消息段数组）。
 */
public final class OneBotEventTranslator {

    @NotNull
    private final OneBot11Messenger messenger;
    private final BotLogger logger;
    private final Consumer<BotMessageEvent> eventCallback;

    // 自身机器人 QQ 号（首帧捕获后缓存）
    private volatile String selfId;

    // 入站去重：最多保留 1024 条 message_id
    private static final int DEDUP_CAPACITY = 1024;
    private final Set<String> recentMessageIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(DEDUP_CAPACITY + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUP_CAPACITY;
                }
            }));

    public OneBotEventTranslator(@NotNull OneBot11Messenger messenger, BotLogger logger,
                                 Consumer<BotMessageEvent> eventCallback) {
        AssertUtil.notNull(messenger, "messenger");
        this.messenger = messenger;
        this.logger = logger;
        this.eventCallback = eventCallback;
    }

    /**
     * 翻译一条原始 OneBot 11 JSON 事件并投递给 eventCallback。
     * 非消息事件（notice / request）暂只记录日志。
     */
    public void translate(String rawJson) {
        try {
            JsonObject json = JsonParser.parseString(rawJson).getAsJsonObject();

            // 捕获 self_id（首次收到的事件会带）
            captureSelfId(json);

            String postType = optString(json, "post_type");
            if (postType == null) {
                // 可能是心跳/元事件
                String metaEventType = optString(json, "meta_event_type");
                if ("heartbeat".equals(metaEventType)) {
                    // NapCat 心跳 PING 由 OneBotEventGateway 处理，这里忽略元事件
                    return;
                }
                log("[WARN] 未知事件类型（无 post_type）: " + rawJson);
                return;
            }

            if ("message".equals(postType)) {
                handleMessageEvent(json, rawJson);
            } else if ("notice".equals(postType)) {
                handleNoticeEvent(json);
            } else if ("request".equals(postType)) {
                handleRequestEvent(json);
            } else {
                log("[WARN] 未处理的事件类型 post_type=" + postType);
            }
        } catch (Exception e) {
            log("[ERROR] 事件翻译异常: " + e.getMessage());
        }
    }

    // ==================== 消息事件 ====================

    private void handleMessageEvent(JsonObject json, String rawJson) {
        String messageType = optString(json, "message_type"); // "group" / "private"
        if (messageType == null) return;

        long msgIdLong = json.has("message_id") ? json.get("message_id").getAsLong() : 0L;
        String msgId = String.valueOf(msgIdLong);

        // 入站去重
        if (msgIdLong != 0 && !recentMessageIds.add(msgId)) {
            log("[DEBUG] 丢弃重复事件 message_id=" + msgId);
            return;
        }

        long userIdLong = json.has("user_id") ? json.get("user_id").getAsLong() : 0L;
        String formId = String.valueOf(userIdLong);
        String senderUid = messenger.toSenderUid(formId);

        // 解析消息内容
        ParsedMessage parsed = parseMessage(json.get("message"), rawJson);

        // 群聊 / 私聊 字段
        String groupId = null;
        boolean isGroup = "group".equals(messageType);
        if (isGroup && json.has("group_id")) {
            groupId = String.valueOf(json.get("group_id").getAsLong());
        }

        // 发送者昵称
        String username = extractSenderName(json);

        // 事件类型
        String effectiveType;
        if (isGroup) {
            effectiveType = parsed.botMentioned ? "OB11_GROUP_AT_MESSAGE_CREATE" : "OB11_GROUP_MESSAGE_CREATE";
        } else {
            effectiveType = "OB11_PRIVATE_MESSAGE_CREATE";
        }

        BotMessageEvent.MessageType msgType = isGroup
                ? BotMessageEvent.MessageType.GROUP
                : BotMessageEvent.MessageType.PRIVATE;

        // 构造回复器
        BotReplier replier = new OneBotReplier(
                messenger.apiClient(), groupId != null ? groupId : formId,
                (int) msgIdLong, 5 * 60 * 1000L);

        BotMessageEvent event = new BotMessageEvent(
                groupId, formId, senderUid,
                parsed.text, replier, username,
                msgType, effectiveType);
        event.setImageUrls(parsed.imageUrls);

        if (eventCallback != null) {
            eventCallback.accept(event);
        }
    }

    // ==================== 通知事件 ====================

    private void handleNoticeEvent(JsonObject json) {
        String noticeType = optString(json, "notice_type");
        if (noticeType == null) return;

        String eventType = "OB11_NOTICE_" + noticeType;
        String groupId = json.has("group_id") ? String.valueOf(json.get("group_id").getAsLong()) : null;
        long userIdLong = json.has("user_id") ? json.get("user_id").getAsLong() : 0L;
        String formId = String.valueOf(userIdLong);
        String senderUid = messenger.toSenderUid(formId);

        // 通知事件大部分无被动回复窗口，用主动回复器
        boolean isGroup = groupId != null;
        BotReplier replier = new OneBotReplier(
                messenger.apiClient(), isGroup ? groupId : formId,
                0, 0); // msgId=0 表示不可被动回复

        BotMessageEvent event = new BotMessageEvent(
                groupId, formId, senderUid,
                "", replier, null,
                isGroup ? BotMessageEvent.MessageType.GROUP : BotMessageEvent.MessageType.PRIVATE,
                eventType);

        if (eventCallback != null) {
            eventCallback.accept(event);
        }
    }

    // ==================== 请求事件 ====================

    private void handleRequestEvent(JsonObject json) {
        String requestType = optString(json, "request_type");
        if (requestType == null) return;

        log("[INFO] 收到加群/加好友请求（request_type=" + requestType
                + "），当前未实现自动处理，请到 OneBot 后端管理");
    }

    // ==================== 消息段解析 ====================

    /**
     * 解析 OB11 message 字段三种形态。
     * 按文档 5.1 决策：只完整解析数组形态；字符串形态 warn 并作为纯文本。
     */
    private ParsedMessage parseMessage(JsonElement messageEl, String rawJson) {
        if (messageEl == null || messageEl.isJsonNull()) {
            return new ParsedMessage("", Collections.emptyList(), null, false);
        }

        // 形态 1：JSON 数组（消息段数组，优先处理）
        if (messageEl.isJsonArray()) {
            return parseMessageSegments(messageEl.getAsJsonArray());
        }

        // 形态 2：字符串（CQ 码或纯文本）
        if (messageEl.isJsonPrimitive()) {
            String text = messageEl.getAsString();
            log("[WARN] message 是字符串形态（非数组），CQ 码不会解析，视为纯文本: " + text);
            return new ParsedMessage(text, Collections.emptyList(), null, false);
        }

        // 形态 3：对象（少见，部分后端特殊情况）
        log("[WARN] message 是非常规形态（非数组/字符串），跳过解析: " + messageEl);
        return new ParsedMessage("", Collections.emptyList(), null, false);
    }

    /**
     * 解析消息段数组。
     */
    private ParsedMessage parseMessageSegments(JsonArray segments) {
        StringBuilder text = new StringBuilder();
        List<String> imageUrls = new ArrayList<>();
        String replyId = null;
        boolean botMentioned = false;

        for (JsonElement el : segments) {
            if (!el.isJsonObject()) continue;
            JsonObject seg = el.getAsJsonObject();
            String type = optString(seg, "type");
            JsonObject data = seg.has("data") && seg.get("data").isJsonObject()
                    ? seg.getAsJsonObject("data") : new JsonObject();

            switch (type != null ? type : "") {
                case "text":
                    String t = optString(data, "text");
                    if (t != null) text.append(t);
                    break;

                case "image":
                    String url = optString(data, "url");
                    String file = optString(data, "file");
                    if (url != null) {
                        imageUrls.add(url.startsWith("http") ? url : "https://" + url);
                    } else if (file != null && file.startsWith("http")) {
                        imageUrls.add(file);
                    }
                    break;

                case "at":
                    String qq = optString(data, "qq");
                    if (qq != null && selfId != null && qq.equals(selfId)) {
                        botMentioned = true;
                        // 不追加到 text（静默剥掉 @机器人 占位）
                    } else if (qq != null) {
                        text.append("@").append(qq).append(" ");
                    }
                    break;

                case "reply":
                    String id = optString(data, "id");
                    if (id != null) replyId = id;
                    // reply 段在文本中不占位
                    break;

                case "record":
                    text.append("[语音]");
                    break;

                case "face":
                case "emoji":
                    text.append("[表情]");
                    break;

                case "markdown":
                    String mdContent = optString(data, "content");
                    if (mdContent != null) text.append(mdContent);
                    break;

                default:
                    // 其他类型（video / file / music 等）→ 占位
                    text.append("[未知消息]");
                    break;
            }
        }

        String finalText = text.toString().trim();
        return new ParsedMessage(finalText, imageUrls, replyId, botMentioned);
    }

    // ==================== 工具方法 ====================

    private void captureSelfId(JsonObject json) {
        if (selfId == null && json.has("self_id") && !json.get("self_id").isJsonNull()) {
            selfId = String.valueOf(json.get("self_id").getAsLong());
            log("[INFO] 捕获 self_id（机器人 QQ 号）: " + selfId);
        }
    }

    private String extractSenderName(JsonObject json) {
        if (json.has("sender") && json.get("sender").isJsonObject()) {
            JsonObject sender = json.getAsJsonObject("sender");
            String card = optString(sender, "card");
            if (card != null && !card.isEmpty()) return card;
            String nickname = optString(sender, "nickname");
            if (nickname != null) return nickname;
        }
        return null;
    }

    private static String optString(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull())
                ? o.get(key).getAsString() : null;
    }

    private void log(String msg) {
        if (logger != null) {
            logger.info("[OneBotTranslator] " + msg);
        }
    }

    /**
     * OneBot 事件翻译结果（内部值类）。
     */
    static final class ParsedMessage {
        final String text;
        final List<String> imageUrls;
        final String replyId;
        final boolean botMentioned;

        ParsedMessage(String text, List<String> imageUrls, String replyId, boolean botMentioned) {
            this.text = text;
            this.imageUrls = imageUrls;
            this.replyId = replyId;
            this.botMentioned = botMentioned;
        }
    }
}
