package org.windy.xingtubot.common.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全项目统一的极简 HTTP 客户端（JDK 自带，无额外依赖）。
 *
 * <p>取代以前散落在各 Service 里手搓的 {@link HttpURLConnection} boilerplate：
 * 统一超时、User-Agent、流读取、错误流处理。各调用方只需保留自己的
 * 重试 / 状态码语义，连接搭建与读流交给本类。
 *
 * <pre>
 *   // GET JSON
 *   Http.Response r = Http.get(url).header("Accept", "application/json").send();
 *   if (r.ok()) parse(r.body);
 *
 *   // POST JSON 带鉴权
 *   String body = Http.post(url).json(payload).header("Authorization", token).send().body;
 *
 *   // 走代理 + 自定义超时
 *   Http.get(url).proxy(proxy).timeout(10000, 15000).send();
 *
 *   // 二进制下载（图片）
 *   byte[] img = Http.get(url).bytes();
 * </pre>
 */
public final class Http {

    public static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    public static final int DEFAULT_READ_TIMEOUT = 15_000;
    public static final String DEFAULT_USER_AGENT = "XingtuBot/1.0";

    private Http() {
    }

    /** HTTP 文本响应：状态码 + 正文（UTF-8；{@code code >= 400} 时取自 errorStream）+ 响应头。 */
    public static final class Response {
        public final int code;
        public final String body;
        private final Map<String, java.util.List<String>> headers;

        public Response(int code, String body, Map<String, java.util.List<String>> headers) {
            this.code = code;
            this.body = body;
            this.headers = headers == null ? java.util.Collections.emptyMap() : headers;
        }

        /** 2xx 视为成功。 */
        public boolean ok() {
            return code >= 200 && code < 300;
        }

        /** 取响应头首值（大小写不敏感）；不存在返回 null。 */
        public String header(String name) {
            for (Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    java.util.List<String> v = e.getValue();
                    return v == null || v.isEmpty() ? null : v.get(0);
                }
            }
            return null;
        }
    }

    public static Request get(String url) {
        return new Request("GET", url);
    }

    public static Request post(String url) {
        return new Request("POST", url);
    }

    public static Request put(String url) {
        return new Request("PUT", url);
    }

    public static Request head(String url) {
        return new Request("HEAD", url);
    }

    /** 请求构造器（链式）。 */
    public static final class Request {
        private final String method;
        private final String url;
        private Proxy proxy = Proxy.NO_PROXY;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;
        private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private int readTimeout = DEFAULT_READ_TIMEOUT;
        private boolean followRedirects = true;

        private Request(String method, String url) {
            this.method = method;
            this.url = url;
            headers.put("User-Agent", DEFAULT_USER_AGENT);
        }

        public Request proxy(Proxy p) {
            if (p != null) this.proxy = p;
            return this;
        }

        public Request header(String key, String value) {
            if (value != null) headers.put(key, value);
            return this;
        }

        public Request userAgent(String ua) {
            return header("User-Agent", ua);
        }

        public Request timeout(int connectMs, int readMs) {
            this.connectTimeout = connectMs;
            this.readTimeout = readMs;
            return this;
        }

        public Request followRedirects(boolean follow) {
            this.followRedirects = follow;
            return this;
        }

        public Request body(byte[] data, String contentType) {
            this.body = data;
            if (contentType != null) headers.put("Content-Type", contentType);
            return this;
        }

        public Request body(String data, String contentType) {
            return body(data.getBytes(StandardCharsets.UTF_8), contentType);
        }

        /** POST JSON 正文（Content-Type: application/json）。 */
        public Request json(String data) {
            return body(data, "application/json");
        }

        /** POST 表单正文（Content-Type: application/x-www-form-urlencoded）。 */
        public Request form(String data) {
            return body(data, "application/x-www-form-urlencoded");
        }

        private HttpURLConnection open() throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(proxy);
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(followRedirects);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            return conn;
        }

        /**
         * 发送并读取文本响应（UTF-8，各行以 {@code \n} 连接）。
         * 不因非 2xx 抛异常——状态码交由调用方按业务语义判断。
         */
        public Response send() throws IOException {
            HttpURLConnection conn = open();
            try {
                int code = conn.getResponseCode();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String text = in == null ? "" : readText(in);
                return new Response(code, text, conn.getHeaderFields());
            } finally {
                conn.disconnect();
            }
        }

        /** 发送并读取二进制响应（如图片下载）。{@code code >= 400} 抛 {@link IOException}。 */
        public byte[] bytes() throws IOException {
            HttpURLConnection conn = open();
            try {
                int code = conn.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    return bos.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        }

        /**
         * 解析重定向：返回 {@code Location} 头（3xx 时），否则返回原 URL。
         * 用于「随机图片」这类 302 到真实图地址的接口。建议配合 {@code head(url).followRedirects(false)}。
         */
        public String resolveRedirect() throws IOException {
            HttpURLConnection conn = open();
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) return loc;
                }
                return url;
            } finally {
                conn.disconnect();
            }
        }
    }

    private static String readText(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            return sb.toString();
        }
    }

    /** URL 编码（UTF-8）。失败返回原文。 */
    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }
}
