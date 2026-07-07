package org.windy.xingtubot.common.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ConcurrentHashMap;

/**
 * IP → 省份 的轻量定位（在线 API，免密钥）。
 *
 * <p><b>隐私底线</b>：只取到<b>省级</b>行政区（如「广东」），<b>不</b>记录/返回 IP 本身、城市、经纬度等更细粒度信息。
 * 内网/保留地址（127./10./192.168./172.16-31./链路本地）直接返回空，不外发。
 *
 * <p>数据源：ip-api.com 免费接口（无需 key，支持 {@code lang=zh-CN} 返回中文省名；免费版约 45 次/分钟）。
 * 结果按 IP 缓存，避免反复进服打满频率。取不到时返回空字符串（调用方应容忍无地区信息）。
 */
public final class IpGeo {

    private IpGeo() {
    }

    // IP → 省份 的缓存（同一玩家反复进服不重复请求）。
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    /**
     * 解析 IP 的省份名（中文）。内网/无效/查询失败一律返回空字符串。
     * <b>会发起网络请求，请在异步线程调用。</b>
     */
    public static String province(String ip) {
        if (ip == null) return "";
        String key = ip.trim();
        if (key.isEmpty() || isPrivate(key)) return "";
        String cached = CACHE.get(key);
        if (cached != null) return cached;

        String province = "";
        try {
            // 只请求 status + regionName 两个字段，最小化获取的数据面（隐私）。
            Http.Response resp = Http.get(
                    "http://ip-api.com/json/" + Http.enc(key) + "?fields=status,regionName&lang=zh-CN")
                    .timeout(5000, 5000)
                    .send();
            if (resp.ok() && resp.body != null && !resp.body.isEmpty()) {
                JsonObject o = JsonParser.parseString(resp.body).getAsJsonObject();
                if (o.has("status") && "success".equals(opt(o, "status"))) {
                    province = opt(o, "regionName");
                }
            }
        } catch (Exception ignored) {
            // 网络/解析失败 → 无地区信息，静默
        }
        if (province == null) province = "";
        CACHE.put(key, province); // 失败也缓存空串，避免反复重试拖慢进服
        return province;
    }

    private static String opt(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** 内网/保留/本地地址判定：这些不外发查询，也无地区意义。 */
    private static boolean isPrivate(String ip) {
        if (ip.equalsIgnoreCase("localhost")) return true;
        if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("169.254.")) return true; // 链路本地
        if (ip.startsWith("172.")) {
            // 172.16.0.0 – 172.31.255.255
            int dot = ip.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(ip.substring(4, dot));
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // IPv6 本地
        if (ip.equals("::1") || ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("fe80")) return true;
        return false;
    }
}
