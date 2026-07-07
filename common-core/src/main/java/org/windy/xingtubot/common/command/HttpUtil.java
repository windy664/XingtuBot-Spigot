package org.windy.xingtubot.common.command;

import org.windy.xingtubot.common.util.Http;

/**
 * 群指令用的极简 HTTP GET 工具。
 *
 * <p>薄封装，实际请求走统一的 {@link Http}；保留本类只为兼容已有的群指令调用方。
 */
public final class HttpUtil {

    private HttpUtil() {
    }

    /** GET 返回文本（UTF-8，已 trim）。失败（非 2xx 或异常）抛出。 */
    public static String get(String url) throws Exception {
        Http.Response r = Http.get(url).userAgent("XingtuBot").timeout(8000, 10000).send();
        if (r.code >= 400) throw new RuntimeException("HTTP " + r.code);
        return r.body.trim();
    }

    /**
     * GET 跟随重定向，返回最终 URL（用于「随机图片」这类 302 到真实图地址的接口，
     * 让 QQ 直接拿到稳定图片地址）。
     */
    public static String resolveRedirect(String url) throws Exception {
        return Http.head(url).userAgent("XingtuBot").timeout(8000, 8000)
                .followRedirects(false).resolveRedirect();
    }

    /** URL 编码。 */
    public static String enc(String s) {
        return Http.enc(s);
    }
}
