package org.windy.xingtubot.common.whitelist;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三端共享的游戏内锁定期文案（真·可配置，非"i18n 假门面"）。
 *
 * <p>默认值即原硬编码简体中文；各平台启动时调一次 {@link #load} 从独立的 {@code messages.yml}
 * 覆盖，服主可自行改措辞。单语言、扁平 key、无 locale 切换。
 *
 * <p>基于 Map，加键只需在 {@link #DEFAULTS} 与 messages.yml 各补一行；取值用 {@link #get}/{@link #format}
 * （支持 {@code {占位符}} 替换）或下方便捷 getter。bossbar/title 会用 packetevents 的 AdventureSerializer
 * 解析 §颜色码保色。
 */
public final class LockMessages {

    /** 键 → 默认文案（= 迁移前的硬编码中文）。加新文案在这里补一行即可。 */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        // —— 锁定期三阶段引导（bossbar / title 复用）——
        DEFAULTS.put("await-qq", "§6欢迎 · 请在聊天框输入你的 QQ号 开始白名单绑定");
        DEFAULTS.put("need-bind", "§e就差一步 · 手持地图用手机 QQ 扫码加群，并在群里发送「绑定」完成头像验证");
        DEFAULTS.put("need-login", "§a欢迎回来 · 在群里点机器人发的「登录」按钮完成上线");
        // —— 聊天反馈 ——
        DEFAULTS.put("qq-invalid", "§c请输入有效的 QQ 号（5-12 位数字）");
        DEFAULTS.put("not-ready", "§c绑定服务未就绪，请稍后再试");
        DEFAULTS.put("unlocked", "§a✅ 已登录，祝游戏愉快！");
        DEFAULTS.put("bound", "§a✅ 已绑定，祝游戏愉快！");
        // —— 加群二维码兜底（{group}/{url}）——
        DEFAULTS.put("qr-fallback", "§e加入 QQ 群：{group} {url}");
        // —— 进服欢迎（Bukkit 本地模式；title 用首个 " · " 拆主/副标题）——
        DEFAULTS.put("welcome-auto-login", "§a§l欢迎回来 · §f同设备信任期内已自动登录");
        DEFAULTS.put("auto-login-msg", "§a✅ 同设备信任期内，已为你自动登录，祝游戏愉快！");
        DEFAULTS.put("welcome-need-login", "§a§l欢迎回来 · {title}");
        DEFAULTS.put("welcome-need-bind", "§6§l请完成绑定 · §f在群里发送「{bind}」");
        DEFAULTS.put("welcome-await-qq", "§6§l欢迎来到本服 · §f请在聊天框输入 QQ 号开始白名单绑定");
        DEFAULTS.put("await-qq-msg", "§e欢迎！请在聊天框输入你的 §bQQ号 §e完成白名单绑定");
    }

    private static volatile Map<String, String> values = new HashMap<>(DEFAULTS);

    private LockMessages() {
    }

    /** messages.yml 的键→值查找（带默认值）。各平台用 {@code YamlBotConfig::getString} 适配。 */
    public interface Lookup {
        String get(String key, String def);
    }

    /** 平台启动时调一次，从 messages.yml 覆盖默认值（缺键则保留默认）。 */
    public static void load(Lookup m) {
        if (m == null) return;
        Map<String, String> v = new HashMap<>(DEFAULTS);
        for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
            v.put(e.getKey(), m.get(e.getKey(), e.getValue()));
        }
        values = v;
    }

    /** 取文案（未知键返回空串）。 */
    public static String get(String key) {
        String v = values.get(key);
        return v != null ? v : "";
    }

    /** 取文案并做占位符替换：{@code format(key, "{a}", va, "{b}", vb)}。 */
    public static String format(String key, String... repl) {
        String s = get(key);
        for (int i = 0; i + 1 < repl.length; i += 2) {
            s = s.replace(repl[i], repl[i + 1] == null ? "" : repl[i + 1]);
        }
        return s;
    }

    // ===== 便捷 getter（保持既有调用点不变）=====

    public static String awaitQq()   { return get("await-qq"); }
    public static String needBind()  { return get("need-bind"); }
    public static String needLogin() { return get("need-login"); }
    public static String qqInvalid() { return get("qq-invalid"); }
    public static String notReady()  { return get("not-ready"); }
    public static String unlocked()  { return get("unlocked"); }

    /** {group}/{url} 占位符替换后的加群兜底文案（packetevents 不可用时用）。 */
    public static String qrFallback(String group, String url) {
        return format("qr-fallback", "{group}", group, "{url}", url);
    }
}
