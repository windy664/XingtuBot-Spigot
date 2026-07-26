package org.windy.xingtubot.module.mcsm.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;
import org.windy.xingtubot.module.mcsm.Sanitizer;

import java.util.Arrays;
import java.util.List;

/**
 * msm 实例 — 实例列表（按节点分组）。
 */
public class MsmListCommand implements BotCommand {

    private final McsmClient client;
    private final boolean sanitize;

    public MsmListCommand(McsmClient client, boolean sanitize) {
        this.client = client;
        this.sanitize = sanitize;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.equals("msm 实例") || m.equals("msm 列表");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        try {
            JsonArray nodes = client.getRemoteNodes();
            if (nodes.size() == 0) {
                event.reply("⚠️ 暂无可用节点");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 📋 实例列表\n");

            int totalRunning = 0, totalCount = 0;

            for (JsonElement el : nodes) {
                JsonObject node = el.getAsJsonObject();
                String daemonId = str(node, "uuid");
                String remarks = str(node, "remarks");
                String nodeName = remarks != null && !remarks.isEmpty() ? remarks : daemonId.substring(0, 8);

                JsonArray instances;
                try {
                    instances = client.listAllInstances(daemonId);
                } catch (McsmClient.McsmException e) {
                    sb.append("\n**").append(nodeName).append("** ⚠️ 拉取失败\n");
                    continue;
                }

                if (instances.size() == 0) continue;

                sb.append("\n**").append(nodeName).append("**\n");

                for (JsonElement ie : instances) {
                    JsonObject inst = ie.getAsJsonObject();
                    // MCSM API 昵称在 config.nickname
                    String name = "";
                    JsonObject cfg = inst.getAsJsonObject("config");
                    if (cfg != null) name = str(cfg, "nickname");
                    if (name.isEmpty()) name = str(inst, "nickname");
                    if (name.isEmpty()) name = str(inst, "instanceUuid");
                    int status = getInt(inst, "status", -1);
                    String statusText = statusIcon(status);
                    totalCount++;
                    if (status == 3) totalRunning++;

                    // 资源信息
                    JsonObject proc = inst.getAsJsonObject("processInfo");
                    String cpu = proc != null ? String.format("%.1f", dbl(proc, "cpu")) : "?";
                    String mem = proc != null ? formatBytes(lng(proc, "memory")) : "?";

                    sb.append("> ").append(statusText).append(" `").append(name).append("`");
                    sb.append(" | CPU ").append(cpu).append("%");
                    sb.append(" | 内存 ").append(mem).append("\n");
                }
            }

            sb.append("\n> 共 ").append(totalCount).append(" 个实例，");
            sb.append(totalRunning).append(" 个运行中");

            event.replyMarkdown(sb.toString(), null);
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 获取实例列表失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-list"; }
    @Override public boolean adminOnly() { return true; }
    @Override public List<String> triggers() { return Arrays.asList("msm 实例", "msm 列表"); }
    @Override public String usage() { return "msm 实例"; }
    @Override public String description() { return "查看所有实例列表"; }
    // category 自动继承模块 displayName

    private static String statusIcon(int status) {
        switch (status) {
            case 3: return "🟢";
            case 0: return "🔴";
            case 1: return "🟡 停止中";
            case 2: return "🟡 启动中";
            case -1: return "⚪ 忙碌";
            default: return "❓";
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
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
