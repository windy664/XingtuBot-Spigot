package org.windy.xingtubot.common.poll;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.api.QqOpenApiClient;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.event.BotReplier;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * QQ 机器人核心：接收 QQ 原始事件，解析后分发；回复通过 OpenAPI 发送。
 *
 * <p>事件来源由外部注入（{@link QQGatewayClient} 等），本类不关心传输层。
 * 对外暴露 {@code addMessageListener(Consumer<BotMessageEvent>)}，插件已有的
 * 监听器与事件总线可原样复用。
 *
 * <p>QQ v2 群@消息事件结构（t = GROUP_AT_MESSAGE_CREATE）：
 * <pre>
 * { "id":"...", "op":0, "t":"GROUP_AT_MESSAGE_CREATE",
 *   "d":{ "id":"msgid", "content":" hi", "group_openid":"G",
 *         "author":{ "member_openid":"U" } } }
 * </pre>
 * 单聊 C2C（t = C2C_MESSAGE_CREATE）：d.author.user_openid，无 group_openid。
 */
public class QqBot {

    private final PlatformAdapter adapter;
    private final QqOpenApiClient api;
    private final Set<String> allowedGroups; // 允许响应的群 openid 集合，空/含"*"=全部
    private final List<Consumer<BotMessageEvent>> listeners = new CopyOnWriteArrayList<>();
    // 同一条消息多次回复需要 msg_seq 自增；这里用全局自增即可（QQ 仅要求同 msg_id 下递增）
    private final AtomicInteger seq = new AtomicInteger(1);

    // 入站事件去重：QQ 网关是「至少投递一次」语义，断线 RESUMED 会重放 lastSeq 之后的事件。
    // 无去重 → 同一条消息重复进游戏 / 同一命令执行两次。这里按 msg_id/event_id 记最近 N 条丢重复。
    private static final int DEDUP_CAPACITY = 1024;
    private final Set<String> recentEventIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(DEDUP_CAPACITY + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUP_CAPACITY;
                }
            }));

    /** 记录并判断事件是否重复：首次见返回 false（放行），已见过返回 true（应丢弃）。 */
    private boolean isDuplicate(String eventKey) {
        if (eventKey == null) return false; // 没有可去重的 id，放行
        return !recentEventIds.add(eventKey);
    }

    public QqBot(PlatformAdapter adapter, QqOpenApiClient api, Set<String> allowedGroups) {
        this.adapter = adapter;
        this.api = api;
        this.allowedGroups = allowedGroups;
    }

    /** 获取底层 OpenAPI 客户端（供主动消息等场景使用）。 */
    public QqOpenApiClient getApi() {
        return api;
    }

    public void addMessageListener(Consumer<BotMessageEvent> listener) {
        listeners.add(listener);
    }

    /**
     * 外部事件注入：供 {@link QQGatewayClient} 等外部来源调用。
     */
    public void handleExternalEvent(String raw) {
        onRawEvent(raw);
    }

    /** 处理一条 QQ 原始事件 JSON。 */
    private void onRawEvent(String raw) {
        adapter.log("[QQ] 收到事件: " + raw);
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            String t = json.has("t") && !json.get("t").isJsonNull() ? json.get("t").getAsString() : "";
            if (!json.has("d") || json.get("d").isJsonNull()) return;
            JsonObject d = json.getAsJsonObject("d");

            // 按钮交互：必须先 ACK（否则客户端转圈到超时），再把按钮 data 当命令投进现有管线
            if ("INTERACTION_CREATE".equals(t)) {
                handleInteraction(d);
                return;
            }

            // 被动回复要用的消息 id：优先 d.id，回退到顶层 id
            String msgId = optString(d, "id");
            if (msgId == null) msgId = optString(json, "id");

            // 区分消息事件 vs 非消息事件：
            // 消息事件（*_MESSAGE_CREATE）用 msg_id 回复；
            // 非消息事件（如 GROUP_ADD_ROBOT、成员加入等）用 event_id 回复。
            boolean isMessageEvent = t.endsWith("_MESSAGE_CREATE");
            String eventId = null;
            if (!isMessageEvent) {
                // 非消息事件：尝试多个位置找 event_id
                eventId = optString(d, "event_id");
                if (eventId == null) eventId = optString(json, "event_id");
                if (eventId == null) eventId = msgId; // d.id 兼容
                msgId = null; // 非消息事件不传 msg_id
            }

            // 入站去重：网关重放/重连会重复投递同一事件，按 t + 消息/事件 id 丢重复
            String dedupKey = (msgId != null ? msgId : eventId);
            if (dedupKey != null && isDuplicate(t + ":" + dedupKey)) {
                adapter.log("[QQ] 丢弃重复事件 t=" + t + " id=" + dedupKey);
                return;
            }

            String content = optString(d, "content");

            // 解析富媒体附件（attachments）：收集图片 URL（转发进游戏用 ChatImage 渲染），
            // 并在正文为空时用 QQ 自带 ASR 转写（asr_refer_text）填充——语音转文字无需外部引擎。
            java.util.List<String> imageUrls = new java.util.ArrayList<>();
            if (d.has("attachments") && d.get("attachments").isJsonArray()) {
                for (com.google.gson.JsonElement el : d.getAsJsonArray("attachments")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject att = el.getAsJsonObject();
                    String ctype = optString(att, "content_type");
                    String url = optString(att, "url");
                    if (url != null && ctype != null && ctype.toLowerCase().startsWith("image")) {
                        imageUrls.add(url.startsWith("http") ? url : "https://" + url); // QQ 偶尔不带协议头
                    }
                    if (content == null || content.trim().isEmpty()) {
                        String asr = optString(att, "asr_refer_text");
                        if (asr != null && !asr.trim().isEmpty()) {
                            content = asr.trim();
                            adapter.log("[QQ] 语音转写(ASR): " + content);
                        }
                    }
                }
            }

            String groupOpenid = optString(d, "group_openid");

            // 群白名单过滤：不在允许列表中的群直接忽略（单聊不受限）
            if (groupOpenid != null && !isGroupAllowed(groupOpenid)) {
                return;
            }

            // 非消息事件：打印 ID 解析详情，方便排查
            if (!isMessageEvent) {
                adapter.log("[QQ] 非消息事件 t=" + t
                        + " msgId=" + msgId + " eventId=" + eventId
                        + " group=" + groupOpenid + " d_keys=" + d.keySet());
            }
            String userOpenid = null;
            String authorUsername = null;
            if (d.has("author") && d.get("author").isJsonObject()) {
                JsonObject author = d.getAsJsonObject("author");
                userOpenid = optString(author, "member_openid");
                if (userOpenid == null) userOpenid = optString(author, "user_openid");
                authorUsername = optString(author, "username");
            }

            // 缓存发送者昵称
            OpenidNameCache nameCache = OpenidNameCache.getInstance();
            if (userOpenid != null && authorUsername != null) {
                nameCache.put(userOpenid, authorUsername);
            }

            // 解析 mentions 数组：缓存被 @ 的用户昵称；并检测「是否@了本机器人」。
            // 注意：QQ 把 @机器人 的群消息也发成 GROUP_MESSAGE_CREATE（非 _AT_），仅靠 mentions[].is_you 标记，
            // 故这里据 is_you 判定被@，并剥掉正文里的 <@bot> 前缀，再当作 @消息处理（否则 mention 模式会被门控掉）。
            boolean botMentioned = false;
            String botMentionId = null;
            if (d.has("mentions") && d.get("mentions").isJsonArray()) {
                for (com.google.gson.JsonElement el : d.getAsJsonArray("mentions")) {
                    if (el.isJsonObject()) {
                        JsonObject mention = el.getAsJsonObject();
                        String mid = optString(mention, "id");
                        String mname = optString(mention, "username");
                        if (mid != null && mname != null) {
                            nameCache.put(mid, mname);
                        }
                        boolean isYou = mention.has("is_you") && mention.get("is_you").isJsonPrimitive()
                                && mention.get("is_you").getAsBoolean();
                        if (isYou) {
                            botMentioned = true;
                            if (mid == null) mid = optString(mention, "member_openid");
                            botMentionId = mid;
                        }
                    }
                }
            }
            // 剥掉本机器人的 @ 前缀，让命令/内容干净（其它人的 @ 保留，供群服互联解析昵称）
            if (botMentioned && content != null && botMentionId != null) {
                content = content.replace("<@" + botMentionId + ">", "").trim();
            }
            // 被@即按 @消息处理（mention 模式不门控、isGroupAtMessage 为真）
            String effectiveType = botMentioned ? "GROUP_AT_MESSAGE_CREATE" : t;

            final String fMsgId = msgId;
            final String fEventId = eventId;
            final String fGroup = groupOpenid;
            final String fUser = userOpenid;
            final boolean isGroup = fGroup != null;

            // 全能回复器：文本/图片/Markdown/Ark 都走 OpenAPI 被动回复（群/单聊各走对应接口）
            // 用命名静态内部类代替匿名内部类，避免 NeoForge shadow jar relocate 导致 NoClassDefFoundError
            BotReplier replier = new OpenApiBotReplier(api, adapter, seq,
                    fGroup, fUser, fMsgId, fEventId, isGroup);

            // 复用现有事件模型：guildId 放会话标识（群/用户 openid），formId 放发送者
            String guildId = isGroup ? fGroup : fUser;
            BotMessageEvent event = new BotMessageEvent(
                    guildId, fUser, content == null ? "" : content.trim(), replier, authorUsername, effectiveType);
            event.setImageUrls(imageUrls); // 群图片 URL 随事件下发，供群服互联拼 ChatImage 码

            for (Consumer<BotMessageEvent> listener : listeners) {
                listener.accept(event);
            }
        } catch (Exception e) {
            adapter.log("[QQ] 事件解析异常: " + e.getMessage());
        }
    }

    /**
     * 处理按钮点击交互（{@code INTERACTION_CREATE}）：
     * <ol>
     *   <li>立刻 ACK（PUT /interactions/{id}）——不回应客户端会一直 loading 到超时；</li>
     *   <li>把按钮携带的 {@code button_data} 当作一条命令消息，投进现有 handler 管线
     *       （= 按钮就是「可点的命令」，复用全部已注册命令/鉴权/markdown 回复，零额外注册）。</li>
     * </ol>
     * 交互无 msg_id/event_id 可挂靠，回复走主动消息（{@link ProactiveReplier}）。
     */
    private void handleInteraction(JsonObject d) {
        String interactionId = optString(d, "id");

        // 入站去重（网关重连会重放）
        if (interactionId != null && isDuplicate("INTERACTION_CREATE:" + interactionId)) {
            return;
        }

        // 先 ACK（成功码 0），异步，别阻塞
        if (interactionId != null) {
            final String fId = interactionId;
            CompletableFuture.runAsync(() -> {
                try {
                    api.ackInteraction(fId, 0);
                } catch (Exception e) {
                    adapter.log("[QQ] 交互 ACK 失败: " + e.getMessage());
                }
            });
        }

        String groupOpenid = optString(d, "group_openid");
        if (groupOpenid != null && !isGroupAllowed(groupOpenid)) return;

        String userOpenid = optString(d, "group_member_openid");
        if (userOpenid == null) userOpenid = optString(d, "user_openid");

        // 取按钮回传数据：d.data.resolved.button_data
        String buttonData = null;
        if (d.has("data") && d.get("data").isJsonObject()) {
            JsonObject data = d.getAsJsonObject("data");
            if (data.has("resolved") && data.get("resolved").isJsonObject()) {
                buttonData = optString(data.getAsJsonObject("resolved"), "button_data");
            }
        }
        if (buttonData == null || buttonData.trim().isEmpty()) return; // 非 callback 按钮（如纯跳转）无 data

        final String fGroup = groupOpenid;
        final String fUser = userOpenid;
        final boolean isGroup = fGroup != null;
        BotReplier replier = new ProactiveReplier(api, adapter, fGroup, fUser, isGroup);
        String guildId = isGroup ? fGroup : fUser;
        BotMessageEvent event = new BotMessageEvent(
                guildId, fUser, buttonData.trim(), replier, null, "INTERACTION_CREATE");
        for (Consumer<BotMessageEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    /** 群是否在白名单中（空集合或含 "*" = 全部允许）。 */
    private boolean isGroupAllowed(String groupOpenid) {
        if (allowedGroups.isEmpty() || allowedGroups.contains("*")) return true;
        return allowedGroups.contains(groupOpenid);
    }

    private static String optString(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }
}
