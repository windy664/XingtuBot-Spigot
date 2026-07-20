package org.windy.xingtubot.module.mcsm.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;
import org.windy.xingtubot.module.mcsm.Sanitizer;

import java.util.Collections;
import java.util.List;

/**
 * msm 详情 <实例名> — 实例详细信息。
 */
public class MsmDetailCommand implements BotCommand {

    private final McsmClient client;
    private final boolean sanitize;
    private final InstanceResolver resolver;

    public MsmDetailCommand(McsmClient client, boolean sanitize, InstanceResolver resolver) {
        this.client = client;
        this.sanitize = sanitize;
        this.resolver = resolver;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.startsWith("msm 详情 ");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String name = message.trim().substring("msm 详情 ".length()).trim();
        if (name.isEmpty()) {
            event.reply("用法: msm 详情 <实例名>");
            return;
        }

        InstanceResolver.Ref ref = resolver.resolve(name);
        if (ref == null) {
            event.reply("❌ 未找到实例: " + name + "\n> 执行 `msm 实例` 查看可用实例列表");
            return;
        }

        try {
            JsonObject resp = client.getInstanceDetail(ref.uuid, ref.daemonId);
            JsonObject data = resp.getAsJsonObject("data");
            if (data == null) {
                event.reply("⚠️ 无实例数据");
                return;
            }

            JsonObject config = data.getAsJsonObject("config");
            JsonObject info = data.getAsJsonObject("info");
            JsonObject proc = data.getAsJsonObject("processInfo");

            String nickname = config != null ? str(config, "nickname") : "";
            if (nickname.isEmpty()) nickname = ref.uuid;
            int status = getInt(data, "status", -1);
            long space = lng(data, "space");
            int started = getInt(data, "started", 0);

            // 进程信息
            double cpu = proc != null ? dbl(proc, "cpu") : 0;
            long memory = proc != null ? lng(proc, "memory") : 0;
            long elapsed = proc != null ? lng(proc, "elapsed") : 0;

            // 游戏信息
            int currentPlayers = info != null ? getInt(info, "currentPlayers", 0) : 0;
            int maxPlayers = info != null ? getInt(info, "maxPlayers", 0) : 0;
            String version = info != null ? str(info, "version") : "";

            StringBuilder sb = new StringBuilder();
            sb.append("## 📊 实例详情 · ").append(nickname).append("\n\n");
            sb.append("**状态** ").append(statusText(status)).append("\n");
            sb.append("**启动次数** ").append(started).append("\n");
            sb.append("**运行时长** ").append(formatElapsed(elapsed)).append("\n");
            sb.append("**占用空间** ").append(formatBytes(space)).append("\n");

            if (!version.isEmpty()) {
                sb.append("**版本** `").append(version).append("`\n");
            }
            if (maxPlayers > 0) {
                sb.append("**玩家** ").append(currentPlayers).append(" / ").append(maxPlayers).append("\n");
            }

            sb.append("\n**CPU** ").append(String.format("%.1f", cpu)).append("%");
            sb.append(" | **内存** ").append(formatBytes(memory)).append("\n");

            event.replyMarkdown(sb.toString(), null);
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 获取实例详情失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-detail"; }
    @Override public boolean adminOnly() { return true; }
    @Override public String usage() { return "msm 详情 <实例名>"; }
    @Override public String description() { return "查看实例详细信息"; }
    @Override public String category() { return "🖥️ 服务器管理"; }

    private static String statusText(int status) {
        switch (status) {
            case 3: return "🟢 运行中";
            case 0: return "🔴 已停止";
            case 1: return "🟡 停止中";
            case 2: return "🟡 启动中";
            case -1: return "⚪ 忙碌";
            default: return "❓ 未知(" + status + ")";
        }
    }

    private static String formatElapsed(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
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
