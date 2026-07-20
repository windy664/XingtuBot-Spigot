package org.windy.xingtubot.module.mcsm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脱敏工具：遮蔽 IP、截断路径、隐藏密码。
 */
public final class Sanitizer {

    private static final Pattern IP_PATTERN =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private Sanitizer() {}

    /**
     * 脱敏 IP 地址：192.168.1.100 → 192.168.*.**
     */
    public static String ip(String ip) {
        if (ip == null || ip.isEmpty()) return ip;
        return maskIp(ip);
    }

    /**
     * 脱敏文件路径：只保留最后一级目录名。
     * /opt/mcsmanager/instances/survival → …/survival
     */
    public static String path(String path) {
        if (path == null || path.isEmpty()) return path;
        // 去掉尾部斜杠
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int lastSlash = p.lastIndexOf('/');
        if (lastSlash < 0) return p;
        String lastSegment = p.substring(lastSlash + 1);
        return "…/" + lastSegment;
    }

    /**
     * 隐藏敏感值（密码/密钥等）。
     */
    public static String secret(String value) {
        if (value == null || value.isEmpty()) return value;
        return "***";
    }

    /**
     * 对文本中的 IP 地址进行脱敏。
     */
    public static String sanitizeText(String text, boolean enabled) {
        if (!enabled || text == null) return text;
        return maskIp(text);
    }

    /** 通用 IP 脱敏（appendReplacement 兼容 Java 8）。 */
    private static String maskIp(String input) {
        Matcher m = IP_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String a = m.group(1);
            String b = m.group(2);
            m.appendReplacement(sb, a + "." + b + ".*.**");
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
