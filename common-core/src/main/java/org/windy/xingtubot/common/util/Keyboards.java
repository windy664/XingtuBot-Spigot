package org.windy.xingtubot.common.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * QQ 消息按钮键盘构造小工具（配合 markdown 消息发送）。
 *
 * <p>QQ 按钮 action.type：0=跳转链接，1=回调（点击推 INTERACTION_CREATE 给 bot），2=指令（插入 @bot data）。
 * 本工具产出 <b>type=1 回调按钮</b>：点击后 data 经交互事件回传，由 {@code QqBot} 自动 ACK 并把 data
 * 当作命令投进消息管线——所以"点按钮"= "发了条命令"，但<b>不</b>在聊天里回显文字，体验干净。
 */
public final class Keyboards {

    private Keyboards() {
    }

    /**
     * 回调按钮键盘：labels 与 datas 等长，每个按钮点击回传对应 data。
     * 最多 25 个（5×5），超出截断；所有人可点。
     */
    public static String callback(List<String> labels, List<String> datas) {
        JsonObject content = new JsonObject();
        JsonArray rows = new JsonArray();
        JsonArray row = new JsonArray();
        int n = Math.min(labels.size(), datas.size());
        int id = 1;
        for (int i = 0; i < n && i < 25; i++) {
            JsonObject btn = new JsonObject();
            btn.addProperty("id", String.valueOf(id++));

            JsonObject rd = new JsonObject();
            rd.addProperty("label", labels.get(i));
            rd.addProperty("visited_label", labels.get(i));
            rd.addProperty("style", 1);
            btn.add("render_data", rd);

            JsonObject action = new JsonObject();
            action.addProperty("type", 1); // 1=回调
            action.addProperty("data", datas.get(i));
            JsonObject perm = new JsonObject();
            perm.addProperty("type", 2);   // 2=所有人可点
            action.add("permission", perm);
            action.addProperty("unsupport_tips", "请升级 QQ 或在搜索后手动操作");
            btn.add("action", action);

            row.add(btn);
            if (row.size() == 5) {
                rows.add(wrapRow(row));   // QQ 要求每行是 {"buttons":[...]} 对象，不能直接塞按钮数组
                row = new JsonArray();
            }
        }
        if (row.size() > 0) rows.add(wrapRow(row));
        content.add("rows", rows);
        JsonObject keyboard = new JsonObject();
        keyboard.add("content", content);
        return keyboard.toString(); // 返回 JSON 字符串：gson 类型不跨插件边界，避免 relocate 失配
    }

    /**
     * 单个回调按钮键盘，且<b>仅指定 openid 可点</b>（permission.type=0 + specify_user_ids）。
     * 其他人点不动、也不会触发交互（QQ 端拦截）——用于「只能本人点」的场景。
     * {@code userOpenid} 为空则退化为所有人可点（type=2）。
     */
    public static String callbackForUser(String label, String data, String userOpenid) {
        JsonObject btn = new JsonObject();
        btn.addProperty("id", "1");

        JsonObject rd = new JsonObject();
        rd.addProperty("label", label);
        rd.addProperty("visited_label", label);
        rd.addProperty("style", 1);
        btn.add("render_data", rd);

        JsonObject action = new JsonObject();
        action.addProperty("type", 1); // 1=回调
        action.addProperty("data", data);
        JsonObject perm = new JsonObject();
        if (userOpenid != null && !userOpenid.isEmpty()) {
            perm.addProperty("type", 0); // 0=指定用户可点
            JsonArray ids = new JsonArray();
            ids.add(userOpenid);
            perm.add("specify_user_ids", ids);
        } else {
            perm.addProperty("type", 2); // 退化：所有人可点
        }
        action.add("permission", perm);
        action.addProperty("unsupport_tips", "请升级 QQ 或在搜索后手动操作");
        btn.add("action", action);

        JsonArray buttons = new JsonArray();
        buttons.add(btn);
        JsonArray rows = new JsonArray();
        rows.add(wrapRow(buttons));
        JsonObject content = new JsonObject();
        content.add("rows", rows);
        JsonObject keyboard = new JsonObject();
        keyboard.add("content", content);
        return keyboard.toString();
    }

    /** QQ 键盘的一行：{"buttons":[...]}。 */
    private static JsonObject wrapRow(JsonArray buttons) {
        JsonObject row = new JsonObject();
        row.add("buttons", buttons);
        return row;
    }
}
