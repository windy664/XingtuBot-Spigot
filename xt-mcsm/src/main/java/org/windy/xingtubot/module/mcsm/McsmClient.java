package org.windy.xingtubot.module.mcsm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.util.Http;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCSManager HTTP API 客户端。
 *
 * <p>封装面板全部 REST 接口，统一处理 apikey 注入、header 设置、错误码语义。
 */
public final class McsmClient {

    private final String baseUrl;
    private final String apiKey;

    public McsmClient(String panelUrl, String apiKey) {
        this.baseUrl = panelUrl;
        this.apiKey = apiKey;
    }

    // ==================== 仪表盘 ====================

    /** 获取面板概览数据（版本/系统/节点列表/实例统计）。 */
    public JsonObject getOverview() throws McsmException {
        return get("/api/overview");
    }

    // ==================== 节点 ====================

    /** 获取节点列表（从 overview.remote 提取）。 */
    public JsonArray getRemoteNodes() throws McsmException {
        JsonObject overview = getOverview();
        JsonObject data = overview.getAsJsonObject("data");
        if (data == null) return new JsonArray();
        JsonArray remote = data.getAsJsonArray("remote");
        return remote != null ? remote : new JsonArray();
    }

    // ==================== 实例 ====================

    /**
     * 获取实例列表。
     *
     * @param daemonId  节点 ID
     * @param page      页码（从 1 开始）
     * @param pageSize  每页条数
     * @param name      实例名过滤（可选）
     * @param status    状态过滤（可选：-1/0/1/2/3）
     */
    public JsonObject listInstances(String daemonId, int page, int pageSize,
                                    String name, String status) throws McsmException {
        StringBuilder path = new StringBuilder("/api/service/remote_service_instances?");
        path.append("daemonId=").append(enc(daemonId));
        path.append("&page=").append(page);
        path.append("&page_size=").append(pageSize);
        if (name != null && !name.isEmpty()) {
            path.append("&instance_name=").append(enc(name));
        }
        if (status != null && !status.isEmpty()) {
            path.append("&status=").append(enc(status));
        }
        return get(path.toString());
    }

    /**
     * 获取全部实例（自动翻页）。
     */
    public JsonArray listAllInstances(String daemonId) throws McsmException {
        JsonArray all = new JsonArray();
        int page = 1;
        while (true) {
            JsonObject resp = listInstances(daemonId, page, 100, null, null);
            JsonObject data = resp.getAsJsonObject("data");
            if (data == null) break;
            JsonArray items = data.getAsJsonArray("data");
            if (items == null || items.size() == 0) break;
            for (JsonElement item : items) {
                all.add(item);
            }
            int maxPage = getInt(data, "maxPage", 1);
            if (page >= maxPage) break;
            page++;
        }
        return all;
    }

    /** 获取单个实例详情。 */
    public JsonObject getInstanceDetail(String uuid, String daemonId) throws McsmException {
        return get("/api/instance?uuid=" + enc(uuid) + "&daemonId=" + enc(daemonId));
    }

    // ==================== 实例操作 ====================

    /** 启动实例。 */
    public void startInstance(String uuid, String daemonId) throws McsmException {
        get("/api/protected_instance/open?uuid=" + enc(uuid) + "&daemonId=" + enc(daemonId));
    }

    /** 停止实例。 */
    public void stopInstance(String uuid, String daemonId) throws McsmException {
        get("/api/protected_instance/stop?uuid=" + enc(uuid) + "&daemonId=" + enc(daemonId));
    }

    /** 重启实例。 */
    public void restartInstance(String uuid, String daemonId) throws McsmException {
        get("/api/protected_instance/restart?uuid=" + enc(uuid) + "&daemonId=" + enc(daemonId));
    }

    /** 强制结束实例进程。 */
    public void killInstance(String uuid, String daemonId) throws McsmException {
        get("/api/protected_instance/kill?uuid=" + enc(uuid) + "&daemonId=" + enc(daemonId));
    }

    /** 向实例发送控制台命令。 */
    public void sendCommand(String uuid, String daemonId, String command) throws McsmException {
        get("/api/protected_instance/command?uuid=" + enc(uuid)
                + "&daemonId=" + enc(daemonId) + "&command=" + enc(command));
    }

    // ==================== 批量操作 ====================

    /**
     * 批量操作。
     *
     * @param operation 操作名：start / stop / restart / kill
     * @param items     [{instanceUuid, daemonId}, ...]
     */
    public void batchOperation(String operation, JsonArray items) throws McsmException {
        String url = baseUrl + "/api/instance/multi_" + operation + "?apikey=" + enc(apiKey);
        try {
            Http.Response resp = Http.post(url)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .json(items.toString())
                    .send();
            checkResponse(resp);
        } catch (IOException e) {
            throw new McsmException("网络请求失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部方法 ====================

    private JsonObject get(String path) throws McsmException {
        String url = path.startsWith("http") ? path : baseUrl + path;
        // MCSM API key 通过查询参数传递，不是 header
        String separator = url.contains("?") ? "&" : "?";
        url = url + separator + "apikey=" + enc(apiKey);
        try {
            Http.Response resp = Http.get(url)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .send();
            return checkResponse(resp);
        } catch (IOException e) {
            throw new McsmException("网络请求失败: " + e.getMessage(), e);
        }
    }

    private JsonObject checkResponse(Http.Response resp) throws McsmException {
        if (resp.code == 403) {
            throw new McsmException("API Key 无权限或已过期 (403)");
        }
        if (resp.code >= 400) {
            throw new McsmException("MCSM 返回错误 HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body).getAsJsonObject();
            int status = getInt(json, "status", 0);
            if (status != 200) {
                throw new McsmException("MCSM 业务错误 status=" + status + ": " + truncate(resp.body, 200));
            }
            return json;
        } catch (McsmException e) {
            throw e;
        } catch (Exception e) {
            throw new McsmException("解析 MCSM 响应失败: " + e.getMessage(), e);
        }
    }

    private static int getInt(JsonObject obj, String key, int def) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try { return el.getAsInt(); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String enc(String s) {
        return Http.enc(s);
    }

    /**
     * MCSM API 异常。
     */
    public static class McsmException extends Exception {
        public McsmException(String message) { super(message); }
        public McsmException(String message, Throwable cause) { super(message, cause); }
    }
}
