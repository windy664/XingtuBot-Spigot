package org.windy.xingtubot.auth.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.util.Http;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Province-level IP lookup used by auth login notices.
 */
public final class IpGeo {

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private IpGeo() {
    }

    public static String province(String ip) {
        if (ip == null) return "";
        String key = ip.trim();
        if (key.isEmpty() || isPrivate(key)) return "";
        String cached = CACHE.get(key);
        if (cached != null) return cached;

        String province = "";
        try {
            Http.Response resp = Http.get(
                    "http://ip-api.com/json/" + Http.enc(key) + "?fields=status,regionName&lang=zh-CN")
                    .timeout(5000, 5000)
                    .send();
            if (resp.ok() && resp.body != null && !resp.body.isEmpty()) {
                JsonObject obj = JsonParser.parseString(resp.body).getAsJsonObject();
                if ("success".equals(opt(obj, "status"))) {
                    province = opt(obj, "regionName");
                }
            }
        } catch (Exception ignored) {
        }
        if (province == null) province = "";
        CACHE.put(key, province);
        return province;
    }

    private static String opt(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static boolean isPrivate(String ip) {
        if (ip.equalsIgnoreCase("localhost")) return true;
        if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("169.254.")) return true;
        if (ip.startsWith("172.")) {
            int dot = ip.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(ip.substring(4, dot));
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ip.equals("::1") || ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("fe80");
    }
}
