package org.windy.xingtubot.module.mcsm.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.util.Pretty;
import org.windy.xingtubot.module.mcsm.McsmClient;
import org.windy.xingtubot.module.mcsm.Sanitizer;

import java.util.Arrays;
import java.util.List;

/**
 * msm / msm 状态 — 面板总览。
 */
public class MsmStatusCommand implements BotCommand {

    private final McsmClient client;
    private final boolean sanitize;

    public MsmStatusCommand(McsmClient client, boolean sanitize) {
        this.client = client;
        this.sanitize = sanitize;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.equals("msm") || m.equals("msm 状态");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        try {
            JsonObject resp = client.getOverview();
            JsonObject data = resp.getAsJsonObject("data");
            if (data == null) {
                event.reply("⚠️ 面板无数据返回");
                return;
            }

            // 面板信息
            String version = str(data, "version");
            double cpu = dbl(data, "process.cpu");
            long mem = lng(data, "process.memory");

            // 系统信息
            JsonObject sys = data.getAsJsonObject("system");
            String hostname = sys != null ? str(sys, "hostname") : "?";
            String platform = sys != null ? str(sys, "platform") : "?";
            double sysCpu = sys != null ? dbl(sys, "cpu") : 0;
            long totalMem = sys != null ? lng(sys, "totalmem") : 0;
            long freeMem = sys != null ? lng(sys, "freemem") : 0;
            long uptime = sys != null ? lng(sys, "uptime") : 0;

            // 节点统计
            JsonObject rc = data.getAsJsonObject("remoteCount");
            int available = rc != null ? getInt(rc, "available", 0) : 0;
            int totalNodes = rc != null ? getInt(rc, "total", 0) : 0;

            // 实例统计
            JsonArray remote = data.getAsJsonArray("remote");
            int runningInst = 0, totalInst = 0;
            if (remote != null) {
                for (JsonElement el : remote) {
                    JsonObject node = el.getAsJsonObject();
                    JsonObject inst = node.getAsJsonObject("instance");
                    if (inst != null) {
                        runningInst += getInt(inst, "running", 0);
                        totalInst += getInt(inst, "total", 0);
                    }
                }
            }

            // 脱敏
            hostname = Sanitizer.sanitizeText(hostname, sanitize);

            StringBuilder sb = new StringBuilder();
            sb.append("## 🖥️ MCSM 面板状态\n\n");
            sb.append("**面板版本** `").append(version).append("`\n");
            sb.append("**主机** `").append(hostname).append("` | **系统** ").append(platform).append("\n");
            sb.append("**面板 CPU** ").append(String.format("%.1f", cpu)).append("%");
            sb.append(" | **面板内存** ").append(formatBytes(mem)).append("\n");
            sb.append("**系统 CPU** ").append(String.format("%.1f", sysCpu * 100)).append("%");
            sb.append(" | **系统内存** ").append(formatBytes(totalMem - freeMem));
            sb.append(" / ").append(formatBytes(totalMem)).append("\n");
            sb.append("**运行时间** ").append(formatUptime(uptime)).append("\n");

            sb.append("\n> **节点** ").append(available).append(" 可用 / ").append(totalNodes).append(" 总计\n");
            sb.append("> **实例** ").append(runningInst).append(" 运行 / ").append(totalInst).append(" 总计\n");

            event.replyMarkdown(sb.toString(), null);
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 获取面板状态失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-status"; }
    @Override public boolean adminOnly() { return true; }
    @Override public List<String> triggers() { return Arrays.asList("msm"); }
    @Override public String usage() { return "msm 状态"; }
    @Override public String description() { return "MCSM 面板总览"; }
    // category 自动继承模块 displayName

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : "";
    }

    private static double dbl(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try { return el.getAsDouble(); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static long lng(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try { return el.getAsLong(); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int getInt(JsonObject o, String key, int def) {
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try { return el.getAsInt(); } catch (Exception ignored) {}
        }
        return def;
    }
}
