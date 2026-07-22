package org.windy.xingtubot.module.mcsm.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;
import org.windy.xingtubot.module.mcsm.Sanitizer;

import java.util.Collections;
import java.util.List;

/**
 * msm 节点 — 节点列表 / 节点详情。
 */
public class MsmNodeCommand implements BotCommand {

    private final McsmClient client;
    private final boolean sanitize;

    public MsmNodeCommand(McsmClient client, boolean sanitize) {
        this.client = client;
        this.sanitize = sanitize;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.equals("msm 节点") || m.startsWith("msm 节点 ");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String args = message.trim().substring("msm 节点".length()).trim();
        try {
            JsonArray nodes = client.getRemoteNodes();
            if (nodes.size() == 0) {
                event.reply("⚠️ 暂无可用节点");
                return;
            }

            // 单节点详情
            if (!args.isEmpty()) {
                showNodeDetail(nodes, args, event);
                return;
            }

            // 节点列表
            StringBuilder sb = new StringBuilder();
            sb.append("## 📡 节点列表\n\n");

            for (JsonElement el : nodes) {
                JsonObject node = el.getAsJsonObject();
                String uuid = str(node, "uuid");
                String ip = str(node, "ip");
                int port = getInt(node, "port", 0);
                String remarks = str(node, "remarks");
                boolean available = node.has("available") && node.get("available").getAsBoolean();
                String status = available ? "🟢 在线" : "🔴 离线";

                // 实例统计
                JsonObject inst = node.getAsJsonObject("instance");
                int running = inst != null ? getInt(inst, "running", 0) : 0;
                int total = inst != null ? getInt(inst, "total", 0) : 0;

                // 资源
                JsonObject sys = node.getAsJsonObject("system");
                double cpu = sys != null ? dbl(sys, "cpuUsage") : 0;
                double mem = sys != null ? dbl(sys, "memUsage") : 0;

                String displayName = remarks != null && !remarks.isEmpty() ? remarks : uuid.substring(0, 8);
                String displayIp = sanitize ? Sanitizer.ip(ip) : ip;

                sb.append("**").append(displayName).append("** ").append(status).append("\n");
                sb.append("> `").append(displayIp).append(":").append(port).append("`");
                sb.append(" | CPU ").append(String.format("%.1f", cpu * 100)).append("%");
                sb.append(" | 内存 ").append(String.format("%.1f", mem * 100)).append("%");
                sb.append(" | 实例 ").append(running).append("/").append(total).append("\n");
            }

            event.replyMarkdown(sb.toString(), null);
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 获取节点信息失败: " + e.getMessage());
        }
    }

    private void showNodeDetail(JsonArray nodes, String name, BotMessageContext event) {
        for (JsonElement el : nodes) {
            JsonObject node = el.getAsJsonObject();
            String uuid = str(node, "uuid");
            String remarks = str(node, "remarks");
            String displayName = remarks != null && !remarks.isEmpty() ? remarks : uuid.substring(0, 8);

            if (!displayName.equalsIgnoreCase(name) && !uuid.startsWith(name)) continue;

            // 找到了
            String ip = str(node, "ip");
            int port = getInt(node, "port", 0);
            boolean available = node.has("available") && node.get("available").getAsBoolean();
            String version = str(node, "version");

            JsonObject sys = node.getAsJsonObject("system");
            String hostname = sys != null ? str(sys, "hostname") : "?";
            String platform = sys != null ? str(sys, "platform") : "?";
            long uptime = sys != null ? lng(sys, "uptime") : 0;
            double cpu = sys != null ? dbl(sys, "cpuUsage") : 0;
            double mem = sys != null ? dbl(sys, "memUsage") : 0;
            long totalMem = sys != null ? lng(sys, "totalmem") : 0;
            long freeMem = sys != null ? lng(sys, "freemem") : 0;

            JsonObject inst = node.getAsJsonObject("instance");
            int running = inst != null ? getInt(inst, "running", 0) : 0;
            int total = inst != null ? getInt(inst, "total", 0) : 0;

            if (sanitize) {
                ip = Sanitizer.ip(ip);
                hostname = Sanitizer.sanitizeText(hostname, true);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 📡 节点详情 · ").append(displayName).append("\n\n");
            sb.append("**状态** ").append(available ? "🟢 在线" : "🔴 离线").append("\n");
            sb.append("**守护进程** `").append(version).append("`\n");
            sb.append("**地址** `").append(ip).append(":").append(port).append("`\n");
            sb.append("**主机** `").append(hostname).append("` | **系统** ").append(platform).append("\n");
            sb.append("**CPU** ").append(String.format("%.1f", cpu * 100)).append("%");
            sb.append(" | **内存** ").append(formatBytes(totalMem - freeMem));
            sb.append(" / ").append(formatBytes(totalMem)).append("\n");
            sb.append("**运行时间** ").append(formatUptime(uptime)).append("\n");
            sb.append("\n> **实例** ").append(running).append(" 运行 / ").append(total).append(" 总计\n");

            event.replyMarkdown(sb.toString(), null);
            return;
        }
        event.reply("❌ 未找到节点: " + name);
    }

    @Override public String name() { return "msm-node"; }
    @Override public boolean adminOnly() { return true; }
    @Override public String usage() { return "msm 节点"; }
    @Override public String description() { return "查看节点列表/详情"; }
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
        if (el != null && el.isJsonPrimitive()) { try { return el.getAsDouble(); } catch (Exception ignored) {} }
        return 0;
    }

    private static long lng(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) { try { return el.getAsLong(); } catch (Exception ignored) {} }
        return 0;
    }

    private static int getInt(JsonObject o, String key, int def) {
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) { try { return el.getAsInt(); } catch (Exception ignored) {} }
        return def;
    }
}
