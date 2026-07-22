package org.windy.xingtubot.module.mcsm.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.module.mcsm.McsmClient;

import java.util.Arrays;
import java.util.List;

/**
 * msm 全启 / msm 全停 / msm 全重启 — 批量操作。
 */
public class MsmBatchCommand implements BotCommand {

    private final McsmClient client;
    private final Runnable markManualOp;

    public MsmBatchCommand(McsmClient client, Runnable markManualOp) {
        this.client = client;
        this.markManualOp = markManualOp;
    }

    @Override
    public boolean matches(String message) {
        String m = message == null ? "" : message.trim();
        return m.equals("msm 全启") || m.equals("msm 全停") || m.equals("msm 全重启");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String m = message.trim();

        // 全启: 启动所有已停止的 (status=0)
        // 全停: 停止所有运行中的 (status=3)
        // 全重启: 重启所有运行中的 (status=3)
        String operation;
        String targetStatus;
        String label;

        if (m.equals("msm 全启")) {
            operation = "start";
            targetStatus = "0";
            label = "启动";
        } else if (m.equals("msm 全停")) {
            operation = "stop";
            targetStatus = "3";
            label = "停止";
        } else {
            operation = "restart";
            targetStatus = "3";
            label = "重启";
        }

        try {
            JsonArray nodes = client.getRemoteNodes();
            JsonArray items = new JsonArray();

            for (JsonElement el : nodes) {
                JsonObject node = el.getAsJsonObject();
                String daemonId = str(node, "uuid");
                try {
                    JsonArray instances = client.listAllInstances(daemonId);
                    for (JsonElement ie : instances) {
                        JsonObject inst = ie.getAsJsonObject();
                        int status = getInt(inst, "status", -1);
                        if (String.valueOf(status).equals(targetStatus)) {
                            JsonObject item = new JsonObject();
                            item.addProperty("instanceUuid", str(inst, "instanceUuid"));
                            item.addProperty("daemonId", daemonId);
                            items.add(item);
                        }
                    }
                } catch (McsmClient.McsmException ignored) {
                }
            }

            if (items.size() == 0) {
                event.reply("ℹ️ 没有需要" + label + "的实例");
                return;
            }

            markManualOp.run();
            client.batchOperation(operation, items);
            event.reply("✅ 已批量" + label + " **" + items.size() + "** 个实例");
        } catch (McsmClient.McsmException e) {
            event.reply("❌ 批量" + label + "失败: " + e.getMessage());
        }
    }

    @Override public String name() { return "msm-batch"; }
    @Override public boolean adminOnly() { return true; }
    @Override public List<String> triggers() { return Arrays.asList("msm 全启", "msm 全停", "msm 全重启"); }
    @Override public String usage() { return "msm 全启/全停/全重启"; }
    @Override public String description() { return "批量启停所有实例"; }
    // category 自动继承模块 displayName

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : "";
    }

    private static int getInt(JsonObject o, String key, int def) {
        JsonElement el = o.get(key);
        if (el != null && el.isJsonPrimitive()) { try { return el.getAsInt(); } catch (Exception ignored) {} }
        return def;
    }
}
