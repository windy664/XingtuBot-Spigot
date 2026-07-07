package org.windy.xingtubot.common.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.util.Http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 百度翻译 API 客户端（通用文本翻译）。
 * <p>
 * 签名规则：MD5(appid + q + salt + key)
 * 接口文档：https://fanyi-api.baidu.com/doc/21
 */
public class BaiduTranslateService implements Translator {

    private static final String API_URL = "https://fanyi-api.baidu.com/api/trans/vip/translate";

    private static Proxy proxy = Proxy.NO_PROXY;

    private final BotLogger logger;

    private String appId = "";
    private String appKey = "";
    private boolean enabled = false;

    public BaiduTranslateService(BotLogger logger) {
        this.logger = logger;
    }

    /**
     * 从 BotConfig 加载凭证并启用/禁用翻译。
     */
    public void loadConfig(org.windy.xingtubot.common.config.BotConfig config) {
        this.appId = config.getString("baidu-translate-appid", "").trim();
        this.appKey = config.getString("baidu-translate-appkey", "").trim();
        this.enabled = !appId.isEmpty() && !appKey.isEmpty();
        if (enabled) {
            info("百度翻译已启用（APPID: " + appId.substring(0, Math.min(4, appId.length())) + "****）");
        } else {
            info("百度翻译未启用（未配置 baidu-translate-appid / baidu-translate-appkey）");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 翻译单条文本（英→中）。
     * 失败时返回原文，不抛异常。
     */
    public String translateEnToZh(String text) {
        if (!enabled || text == null || text.trim().isEmpty()) return text;
        // 纯 ASCII 符号/数字/空格的短文本不翻译
        if (text.trim().length() < 3 || text.matches("^[\\x00-\\x7F\\s]+$") && text.trim().matches("^[\\d.\\-/\\s]+$")) {
            return text;
        }
        try {
            return doTranslate(text, "en", "zh");
        } catch (Exception e) {
            warn("翻译失败，返回原文: " + e.getMessage());
            return text;
        }
    }

    /**
     * 翻译单条文本（中→英）。
     * 失败时返回原文，不抛异常。
     */
    public String translateZhToEn(String text) {
        if (!enabled || text == null || text.trim().isEmpty()) return text;
        try {
            return doTranslate(text, "zh", "en");
        } catch (Exception e) {
            warn("翻译失败，返回原文: " + e.getMessage());
            return text;
        }
    }

    /**
     * 批量翻译多条文本（减少 API 调用次数，用换行符拼接）。
     * 失败时返回原文列表。
     */
    public java.util.List<String> batchTranslateEnToZh(java.util.List<String> texts) {
        if (!enabled || texts == null || texts.isEmpty()) return texts;

        // 过滤出需要翻译的，短/纯数字的直接跳过
        java.util.List<String> result = new java.util.ArrayList<>(texts);
        StringBuilder batch = new StringBuilder();
        java.util.List<Integer> indices = new java.util.ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t != null && t.trim().length() >= 3
                    && !t.matches("^[\\d.\\-/\\s]+$")) {
                if (batch.length() > 0) batch.append("\n");
                batch.append(t);
                indices.add(i);
            }
        }

        if (indices.isEmpty()) return result;

        try {
            String translated = doTranslate(batch.toString(), "en", "zh");
            String[] parts = translated.split("\n");
            for (int i = 0; i < indices.size() && i < parts.length; i++) {
                result.set(indices.get(i), parts[i]);
            }
        } catch (Exception e) {
            warn("批量翻译失败，返回原文: " + e.getMessage());
        }
        return result;
    }

    // ==================== 内部实现 ====================

    private String doTranslate(String query, String from, String to) throws IOException {
        int salt = ThreadLocalRandom.current().nextInt(32768, 65536);
        String sign = md5(appId + query + salt + appKey);

        String body = "q=" + urlEncode(query)
                + "&from=" + from
                + "&to=" + to
                + "&appid=" + urlEncode(appId)
                + "&salt=" + salt
                + "&sign=" + sign;

        Http.Response resp = Http.post(API_URL).proxy(proxy).form(body).send();
        if (resp.code != 200) {
            throw new IOException("HTTP " + resp.code);
        }

        JsonObject root = JsonParser.parseString(resp.body).getAsJsonObject();

        // 错误处理
        if (root.has("error_code")) {
            String errorCode = root.get("error_code").getAsString();
            String errorMsg = root.has("error_msg") ? root.get("error_msg").getAsString() : "";
            throw new IOException("百度翻译错误 " + errorCode + ": " + errorMsg);
        }

        JsonArray transResult = root.getAsJsonArray("trans_result");
        if (transResult == null || transResult.size() == 0) {
            throw new IOException("翻译结果为空");
        }

        // 多条结果用换行拼接
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < transResult.size(); i++) {
            if (i > 0) result.append("\n");
            JsonObject item = transResult.get(i).getAsJsonObject();
            result.append(item.get("dst").getAsString());
        }
        return result.toString();
    }

    // ==================== 工具 ====================

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static void setProxy(String host, int port, String type) {
        if (host == null || host.isEmpty() || type == null) {
            proxy = Proxy.NO_PROXY;
            return;
        }
        SocketAddress addr = new InetSocketAddress(host, port);
        switch (type.toLowerCase()) {
            case "socks":
                proxy = new Proxy(Proxy.Type.SOCKS, addr);
                break;
            case "http":
                proxy = new Proxy(Proxy.Type.HTTP, addr);
                break;
            default:
                proxy = Proxy.NO_PROXY;
                break;
        }
    }

    private void info(String msg) {
        if (logger != null) logger.info("[BaiduTranslate] " + msg);
    }

    private void warn(String msg) {
        if (logger != null) logger.warn("[BaiduTranslate] " + msg);
    }
}
