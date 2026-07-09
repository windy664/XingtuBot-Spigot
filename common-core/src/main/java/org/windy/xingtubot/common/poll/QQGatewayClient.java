package org.windy.xingtubot.common.poll;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.util.Http;
import org.windy.xingtubot.common.util.Pretty;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * QQ 官方 Bot WebSocket 网关客户端。
 *
 * <p>参考 openclaw 的 gateway-connection.ts 实现，协议流程：
 * <ol>
 *   <li>GET {@code /gateway} 拿到 wss 地址</li>
 *   <li>连接 → 收 HELLO (op 10) → 发 IDENTIFY (op 2) + intents + token</li>
 *   <li>心跳 HEARTBEAT (op 1) 保活</li>
 *   <li>收事件 DISPATCH (op 0) → 回调传原始 JSON</li>
 *   <li>断线自动重连 + RESUME (op 6) 恢复会话</li>
 * </ol>
 *
 * <p>启动后通过 {@code Consumer<String>} 回调原始事件 JSON。
 * 上层 {@link QqBot} 不关心事件来源，两种模式完全复用。
 */
public class QQGatewayClient {

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String API_BASE = "https://api.sgroup.qq.com";

    // QQ Gateway Op codes
    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_PRESENCE_UPDATE = 3;
    private static final int OP_VOICE_STATE_UPDATE = 4;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_REQUEST_GUILD_MEMBERS = 8;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;

    // Intents
    private static final int INTENT_GROUP_AND_C2C = 1 << 25;   // 群消息 + C2C
    private static final int INTENT_INTERACTION = 1 << 26;     // 按钮交互
    private static final int INTENTS = INTENT_GROUP_AND_C2C | INTENT_INTERACTION;

    private final String appId;
    private final String clientSecret;
    private final Consumer<String> eventCallback;
    private final BotLogger logger;
    private final java.util.function.BooleanSupplier debug; // debug=true 才打逐事件类型日志
    private volatile Consumer<String> onBotNameResolved; // 获取到机器人名字后的回调

    // Token 缓存
    private volatile String accessToken;
    private volatile long tokenExpireAt;
    private final Object tokenLock = new Object();

    // 会话状态（用于 RESUME）
    private volatile String sessionId;
    private volatile int lastSeq = -1;

    // 连接管理
    private volatile GatewayWebSocket currentWs;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> tokenRefreshTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean manualStop = new AtomicBoolean(false);

    // 重连
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT = 100;
    private static final long[] RECONNECT_DELAYS = {1000, 2000, 5000, 10000, 30000, 60000};
    private static final long RATE_LIMIT_DELAY = 60_000; // 限频后固定等 60 秒（参考 openclaw/hermes-agent）

    // 快速断连检测（参考 openclaw constants.ts）
    private static final long QUICK_DISCONNECT_THRESHOLD = 5000; // 5 秒内断开算"快速断连"
    private static final int MAX_QUICK_DISCONNECT_COUNT = 3;     // 连续 3 次快速断连 → 强制限频等待
    private int quickDisconnectCount = 0;
    private long lastConnectTime = 0;

    // Gateway URL 缓存 — 重连时复用，避免在限频期间再请求 /gateway 端点
    private volatile String cachedGatewayUrl;

    public QQGatewayClient(String appId, String clientSecret,
                           Consumer<String> eventCallback, BotLogger logger) {
        this(appId, clientSecret, eventCallback, logger, () -> false);
    }

    public QQGatewayClient(String appId, String clientSecret,
                           Consumer<String> eventCallback, BotLogger logger,
                           java.util.function.BooleanSupplier debug) {
        this.appId = appId;
        this.clientSecret = clientSecret;
        this.eventCallback = eventCallback;
        this.logger = logger;
        this.debug = debug != null ? debug : () -> false;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QQ-Gateway-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        manualStop.set(false);
        reconnectAttempts = 0;
        quickDisconnectCount = 0;
        cachedGatewayUrl = null; // 每次启动重新获取
        scheduler.execute(this::connect);
    }

    public void stop() {
        manualStop.set(true);
        running.set(false);
        stopHeartbeat();
        stopTokenRefresh();
        GatewayWebSocket ws = currentWs;
        if (ws != null) {
            try { ws.close(); } catch (Throwable ignored) {} // Throwable: 捕获 relocate 导致的 NoClassDefFoundError
        }
        scheduler.shutdownNow();
        log("已停止");
    }

    public boolean isRunning() {
        return running.get() && currentWs != null && currentWs.isOpen();
    }

    /** 设置机器人名字回调：连接成功后从 API 获取到名字时调用。 */
    public void setOnBotNameResolved(Consumer<String> callback) {
        this.onBotNameResolved = callback;
    }

    // ========================= 连接 =========================

    private void connect() {
        try {
            // 清理旧连接
            GatewayWebSocket old = currentWs;
            if (old != null) {
                try { old.close(); } catch (Exception ignored) {}
            }

            // 获取 token
            String token = getAccessToken();
            log("✅ Access token 获取成功");

            // 获取 gateway URL — 优先用缓存，减少限频期间的 HTTP 请求
            String gatewayUrl = cachedGatewayUrl;
            if (gatewayUrl == null) {
                gatewayUrl = getGatewayUrl(token);
                cachedGatewayUrl = gatewayUrl; // 成功才缓存
            }
            log("连接网关: " + gatewayUrl);

            // 创建 WebSocket
            GatewayWebSocket ws = new GatewayWebSocket(new URI(gatewayUrl));
            this.currentWs = ws;
            ws.connectBlocking(10, TimeUnit.SECONDS);

        } catch (Exception e) {
            String msg = e.getMessage();
            log("连接失败: " + msg);

            // 检测限频错误（HTTP 400 + code 100017），强制等 60 秒
            if (msg != null && msg.contains("100017")) {
                log("⚠️ 触发频率限制，等待 " + (RATE_LIMIT_DELAY / 1000) + " 秒后重试");
                // 保留 cachedGatewayUrl — URL 不变，限频只是拿不到新的
                scheduleReconnect(RATE_LIMIT_DELAY);
            } else {
                // 非限频错误（如鉴权失败），清除缓存下次重新获取
                cachedGatewayUrl = null;
                scheduleReconnect();
            }
        }
    }

    // ========================= WebSocket 事件处理 =========================

    private void onWsOpen() {
        log("WebSocket 已连接，等待 HELLO...");
        reconnectAttempts = 0;
        lastConnectTime = System.currentTimeMillis();
    }

    private void onWsMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            int op = json.has("op") ? json.get("op").getAsInt() : -1;

            // 记录 seq
            if (json.has("s") && !json.get("s").isJsonNull()) {
                lastSeq = json.get("s").getAsInt();
            }

            switch (op) {
                case OP_HELLO:
                    handleHello(json);
                    break;
                case OP_DISPATCH:
                    handleDispatch(json);
                    break;
                case OP_HEARTBEAT_ACK:
                    // 心跳确认，什么都不做
                    break;
                case OP_RECONNECT:
                    log("服务端要求重连");
                    scheduleReconnect(0);
                    break;
                case OP_INVALID_SESSION:
                    boolean canResume = json.has("d") && json.get("d").getAsBoolean();
                    log("会话无效，canResume=" + canResume);
                    if (!canResume) {
                        sessionId = null;
                        lastSeq = -1;
                    }
                    scheduleReconnect(3000);
                    break;
                default:
                    // 忽略其他 op
                    break;
            }
        } catch (Exception e) {
            log("消息解析异常: " + e.getMessage());
        }
    }

    private void onWsClose(int code, String reason) {
        String meaning;
        switch (code) {
            case 4001: meaning = "参数错误"; break;
            case 4002: meaning = "鉴权失败（token 无效）"; break;
            case 4003: meaning = "机器人已下线"; break;
            case 4004: meaning = "重复连接被踢"; break;
            case 4005: meaning = "服务端内部错误"; break;
            case 4006: meaning = "服务端繁忙"; break;
            case 4007: meaning = "心跳超时"; break;
            case 4008: meaning = "限频"; break;
            case 4009: meaning = "Session 过期"; break;
            case 4010: meaning = "分片无效"; break;
            case 4011: meaning = "分片过大"; break;
            case 4012: meaning = "无效的 opcode"; break;
            case 4013: meaning = "未注册的意图(intents)"; break;
            case 4014: meaning = "意图(intents)未授权 — 请在 QQ 开放平台后台开启对应权限"; break;
            case 4914: meaning = "该机器人不允许当前 intents 组合"; break;
            case 4915: meaning = "该机器人不允许当前 intents（被禁用）"; break;
            default: meaning = ""; break;
        }
        log("WebSocket 关闭: code=" + code + (meaning.isEmpty() ? "" : " (" + meaning + ")") + " reason=" + reason);
        stopHeartbeat();

        if (manualStop.get()) return;

        // 4014/4914/4915 = 权限问题，不重连
        if (code == 4014 || code == 4914 || code == 4915) {
            log("❌ 权限不足！请在 QQ 开放平台 → 机器人 → 权限管理 中开启「接收群聊消息」和「群聊@消息」权限");
            log("   权限申请指南: https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/event-subscribe.html");
            running.set(false);
            return;
        }

        // 4008 = 限频，固定等 60 秒（参考 openclaw/hermes-agent）
        if (code == 4008) {
            log("⚠️ 收到限频关闭码(4008)，等待 " + (RATE_LIMIT_DELAY / 1000) + " 秒后重连");
            scheduleReconnect(RATE_LIMIT_DELAY);
            return;
        }

        // 4002/4004 = 鉴权失败/被踢，清除 token 和缓存的 gateway URL
        if (code == 4002 || code == 4004) {
            log("⚠️ 鉴权相关关闭码(" + code + ")，清除 token 缓存");
            accessToken = null;
            tokenExpireAt = 0;
            cachedGatewayUrl = null;
        }

        // 4009 = Session 过期，清除会话状态强制重新 IDENTIFY
        if (code == 4009) {
            log("Session 过期，清除会话状态");
            sessionId = null;
            lastSeq = -1;
        }

        // 快速断连检测：连接后 5 秒内断开 → 可能是权限/配置问题
        long duration = lastConnectTime > 0 ? System.currentTimeMillis() - lastConnectTime : Long.MAX_VALUE;
        if (duration < QUICK_DISCONNECT_THRESHOLD && lastConnectTime > 0) {
            quickDisconnectCount++;
            log("⚡ 快速断连（" + duration + "ms 内），累计 " + quickDisconnectCount + "/" + MAX_QUICK_DISCONNECT_COUNT + " 次");
            if (quickDisconnectCount >= MAX_QUICK_DISCONNECT_COUNT) {
                log("⚠️ 连续快速断连 " + MAX_QUICK_DISCONNECT_COUNT + " 次，可能权限或配置有问题，等待 60 秒");
                quickDisconnectCount = 0;
                scheduleReconnect(RATE_LIMIT_DELAY);
                return;
            }
        } else {
            quickDisconnectCount = 0; // 正常连接后重置
        }

        scheduleReconnect();
    }

    private void onWsError(Exception ex) {
        log("WebSocket 异常: " + ex.getMessage());
    }

    // ========================= 协议处理 =========================

    private void handleHello(JsonObject json) {
        JsonObject d = json.getAsJsonObject("d");
        int heartbeatInterval = d.has("heartbeat_interval") ? d.get("heartbeat_interval").getAsInt() : 41250;

        // 发送 IDENTIFY 或 RESUME
        if (sessionId != null && lastSeq >= 0) {
            log("尝试 RESUME（sessionId=" + sessionId + ", seq=" + lastSeq + "）");
            JsonObject resume = new JsonObject();
            resume.addProperty("op", OP_RESUME);
            JsonObject resumeD = new JsonObject();
            resumeD.addProperty("token", "QQBot " + accessToken);
            resumeD.addProperty("session_id", sessionId);
            resumeD.addProperty("seq", lastSeq);
            resume.add("d", resumeD);
            currentWs.send(resume.toString());
        } else {
            log("发送 IDENTIFY（intents=" + INTENTS + "）");
            JsonObject identify = new JsonObject();
            identify.addProperty("op", OP_IDENTIFY);
            JsonObject identifyD = new JsonObject();
            identifyD.addProperty("token", "QQBot " + accessToken);
            identifyD.addProperty("intents", INTENTS);
            JsonObject shard = new JsonObject();
            // shard: [shard_id, num_shards]
            com.google.gson.JsonArray shardArr = new com.google.gson.JsonArray();
            shardArr.add(0);
            shardArr.add(1);
            identifyD.add("shard", shardArr);
            identify.add("d", identifyD);
            currentWs.send(identify.toString());
        }

        // 启动心跳
        startHeartbeat(heartbeatInterval);
    }

    private void handleDispatch(JsonObject json) {
        String t = json.has("t") && !json.get("t").isJsonNull() ? json.get("t").getAsString() : "";

        if ("READY".equals(t)) {
            JsonObject d = json.getAsJsonObject("d");
            sessionId = d.has("session_id") ? d.get("session_id").getAsString() : null;
            startTokenRefresh();
            // 连接成功，缓存当前 gateway URL 供后续重连使用
            GatewayWebSocket ws = currentWs;
            if (ws != null && ws.getURI() != null) {
                cachedGatewayUrl = ws.getURI().toString();
            }
            // 获取机器人详细信息并输出
            scheduler.execute(this::probePermissions);
            return;
        }

        if ("RESUMED".equals(t)) {
            log("✅ 会话恢复成功");
            // 连接成功，缓存当前 gateway URL
            GatewayWebSocket ws = currentWs;
            if (ws != null && ws.getURI() != null) {
                cachedGatewayUrl = ws.getURI().toString();
            }
            return;
        }

        // 记录收到的事件类型（仅 debug 模式）
        if (debug.getAsBoolean()) log("收到事件: " + t);

        // 其他事件 → 传给上层解析
        if (eventCallback != null) {
            try {
                eventCallback.accept(json.toString());
            } catch (Exception e) {
                log("事件回调异常: " + e.getMessage());
            }
        }
    }

    /**
     * 连接成功后调一次 API，验证 token 可用 + 输出机器人详细信息。
     */
    private void probePermissions() {
        try {
            String token = getAccessToken();
            // GET /users/@me — 获取机器人自身信息
            Http.Response resp = Http.get(API_BASE + "/users/@me")
                    .header("Authorization", "QQBot " + token)
                    .header("Content-Type", "application/json")
                    .timeout(10000, 10000)
                    .send();

            int code = resp.code;
            if (code == 200) {
                JsonObject me = JsonParser.parseString(resp.body).getAsJsonObject();
                String botName = me.has("username") ? me.get("username").getAsString() : "?";
                String botId = me.has("id") ? me.get("id").getAsString() : "?";
                String desc = me.has("desc") ? me.get("desc").getAsString() : "";
                String avatar = me.has("avatar") ? me.get("avatar").getAsString() : "";

                // 机器人昵称单一真源：直接写入 BotIdentity（取代旧的 config bot-name 回写）。
                if (!"?".equals(botName)) {
                    org.windy.xingtubot.common.api.BotIdentity.setName(botName);
                }
                // 回调保留（可选订阅，如日志/二次用途）
                Consumer<String> nameCallback = onBotNameResolved;
                if (nameCallback != null && !"?".equals(botName)) {
                    try { nameCallback.accept(botName); } catch (Exception ignored) {}
                }

                infoHeader();
                kvLog("名称", botName);
                kvLog("ID", botId);
                if (!desc.isEmpty()) {
                    kvLog("简介", desc.length() > 50 ? desc.substring(0, 50) + "..." : desc);
                }
                if (!avatar.isEmpty()) {
                    kvLog("头像", avatar);
                }
                kvLog("网关", "已连接 (sessionId=" + sessionId + ")");
                kvLog("API", "验证通过 ✅");
                infoFooter();
            } else {
                infoHeader();
                kvLog("网关", "已连接 (sessionId=" + sessionId + ")");
                kvLog("API", "❌ 验证失败 (HTTP " + code + ")");
                kvLog("错误", resp.body);
                if (code == 401) {
                    kvLog("提示", "💡 请检查 openapi-app-id 和 openapi-client-secret");
                }
                infoFooter();
            }
        } catch (Exception e) {
            infoHeader();
            kvLog("网关", "已连接 (sessionId=" + sessionId + ")");
            kvLog("API", "⚠️ 异常 — " + e.getMessage());
            infoFooter();
        }
    }

    private void infoHeader() {
        log("");
        log("──────────  🤖 机器人信息  ──────────");
    }

    private void infoFooter() {
        log("────────────────────────────────────");
    }

    /** 「机器人信息」分栏行：键按显示宽度对齐到 6 格。 */
    private void kvLog(String label, String value) {
        log("   " + Pretty.padEnd(label, 6) + value);
    }

    // ========================= 心跳 =========================

    private void startHeartbeat(int intervalMs) {
        stopHeartbeat();
        // 先发一次心跳
        scheduler.execute(() -> sendHeartbeat());
        // 定时心跳（间隔稍微短一点，避免超时）
        long interval = Math.max(intervalMs - 5000, 10000);
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        GatewayWebSocket ws = currentWs;
        if (ws == null || !ws.isOpen()) return;
        JsonObject hb = new JsonObject();
        hb.addProperty("op", OP_HEARTBEAT);
        hb.addProperty("d", lastSeq >= 0 ? lastSeq : null);
        try {
            ws.send(hb.toString());
        } catch (Exception e) {
            log("心跳发送失败: " + e.getMessage());
        }
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
    }

    // ========================= Token 自动刷新 =========================

    private void startTokenRefresh() {
        stopTokenRefresh();
        // 每 50 分钟刷新一次（token 有效期通常 7200 秒 = 120 分钟）
        tokenRefreshTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                synchronized (tokenLock) {
                    accessToken = null; // 强制刷新
                    getAccessToken();
                    log("Token 已刷新");
                }
            } catch (Exception e) {
                log("Token 刷新失败: " + e.getMessage());
            }
        }, 50, 50, TimeUnit.MINUTES);
    }

    private void stopTokenRefresh() {
        if (tokenRefreshTask != null) {
            tokenRefreshTask.cancel(true);
            tokenRefreshTask = null;
        }
    }

    // ========================= 重连 =========================

    private void scheduleReconnect() {
        scheduleReconnect(-1);
    }

    private void scheduleReconnect(long delayMs) {
        if (manualStop.get() || !running.get()) return;
        if (reconnectAttempts >= MAX_RECONNECT) {
            log("重连次数已达上限（" + MAX_RECONNECT + "），停止重连");
            running.set(false);
            return;
        }

        long delay = delayMs >= 0 ? delayMs :
                RECONNECT_DELAYS[Math.min(reconnectAttempts, RECONNECT_DELAYS.length - 1)];
        reconnectAttempts++;
        log("第 " + reconnectAttempts + " 次重连，" + delay + "ms 后...");
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    // ========================= Token / Gateway API =========================

    private String getAccessToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return accessToken;
        }
        synchronized (tokenLock) {
            if (accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
                return accessToken;
            }
            JsonObject body = new JsonObject();
            body.addProperty("appId", appId);
            body.addProperty("clientSecret", clientSecret);

            Http.Response tokenResp = Http.post(TOKEN_URL).json(body.toString()).timeout(10000, 15000).send();
            String resp = httpResult(tokenResp);
            JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
            if (!json.has("access_token")) {
                throw new IOException("获取 access_token 失败: " + resp);
            }
            this.accessToken = json.get("access_token").getAsString();
            long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 7200L;
            this.tokenExpireAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L;
            return accessToken;
        }
    }

    private String getGatewayUrl(String token) throws IOException {
        Http.Response gwResp = Http.get(API_BASE + "/gateway")
                .header("Authorization", "QQBot " + token)
                .header("Content-Type", "application/json")
                .timeout(10000, 15000)
                .send();
        String resp = httpResult(gwResp);
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
        if (!json.has("url")) {
            throw new IOException("获取 gateway URL 失败: " + resp);
        }
        return json.get("url").getAsString();
    }

    private String httpResult(Http.Response resp) throws IOException {
        if (resp.code >= 400) {
            throw new IOException("HTTP " + resp.code + ": " + resp.body);
        }
        return resp.body;
    }

    // ========================= 日志 =========================

    private void log(String msg) {
        if (logger != null) {
            logger.info("[Gateway] " + msg);
        }
    }

    // ========================= 内部 WebSocket 类 =========================

    private class GatewayWebSocket extends WebSocketClient {

        GatewayWebSocket(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            onWsOpen();
        }

        @Override
        public void onMessage(String message) {
            onWsMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            onWsClose(code, reason);
        }

        @Override
        public void onError(Exception ex) {
            onWsError(ex);
        }
    }
}
