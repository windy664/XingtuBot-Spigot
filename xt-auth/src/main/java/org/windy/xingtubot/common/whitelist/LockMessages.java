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
        DEFAULTS.put("need-login-title", "@{bot} 登录");
        DEFAULTS.put("login-button-label", "✅ 同意登录");
        DEFAULTS.put("welcome-need-login", "§a§l欢迎回来 · {title}");
        DEFAULTS.put("welcome-need-bind", "§6§l请完成绑定 · §f在群里发送「{bind}」");
        DEFAULTS.put("welcome-await-qq", "§6§l欢迎来到本服 · §f请在聊天框输入 QQ 号开始白名单绑定");
        DEFAULTS.put("await-qq-msg", "§e欢迎！请在聊天框输入你的 §bQQ号 §e完成白名单绑定");
        // —— 绑定/登录反馈（BindingServiceImpl）——
        DEFAULTS.put("qq-format", "§cQQ号格式不正确，请输入 5~15 位数字");
        DEFAULTS.put("already-bound", "§e你已绑定过白名单，无需重复绑定");
        DEFAULTS.put("qq-avatar-fail", "§c取不到该QQ号的头像，请确认QQ号填写正确、且该QQ已设置头像后重试");
        DEFAULTS.put("qq-avatar-unusable", "§c该QQ头像是默认/纯色或无法识别，无法用于绑定。请先在QQ设置一张清晰头像后再绑定");
        DEFAULTS.put("qq-recorded", "§a已记录QQ §f{qq}§a。\n§a请在 QQ群 §e{group} §a内发送「§e§l{prompt}§a」完成白名单绑定（5分钟内有效）");
        DEFAULTS.put("bind-title", "§a§l✅ 绑定成功");
        DEFAULTS.put("bind-subtitle", "§f欢迎加入，祝游戏愉快");
        DEFAULTS.put("bind-msg", "§a✅ 绑定成功！已为你注册，可以正常游玩了");
        DEFAULTS.put("login-title", "§a§l✅ 登录成功");
        DEFAULTS.put("login-subtitle", "§f祝你游戏愉快");
        DEFAULTS.put("login-msg", "§a✅ 你已通过QQ群登录，祝游戏愉快！");
        DEFAULTS.put("command-blocked", "§c请先完成绑定/登录后再使用命令");
        // —— 群聊侧·绑定/登录错误回复 ——
        DEFAULTS.put("group-already-bound", "⚠️ 您的QQ已绑定过白名单");
        DEFAULTS.put("group-too-many-attempts", "⚠️ 尝试次数过多，请稍后再试");
        DEFAULTS.put("group-no-pending", "⚠️ 当前没有待绑定的记录，请先在游戏内输入你的 QQ 号");
        DEFAULTS.put("group-appid-empty", "⚠️ 绑定服务尚未就绪（openapi-app-id 为空），请稍候重试或联系管理员");
        DEFAULTS.put("group-avatar-fetch-fail", "⚠️ 取不到你的QQ头像，暂时无法绑定，请稍后再试");
        DEFAULTS.put("group-avatar-placeholder", "⚠️ 绑定服务未正确配置（管理员请检查 openapi-app-id 是否与机器人一致），暂时无法绑定");
        DEFAULTS.put("group-avatar-low-info", "⚠️ 你的QQ头像是默认/纯色头像，无法用于绑定，请先在QQ设置一张清晰头像后重试");
        DEFAULTS.put("group-no-match", "⚠️ 没找到与你头像匹配的待绑定记录。请确认：游戏内输入的是【你本人】的QQ号，且与你现在的QQ头像一致");
        DEFAULTS.put("group-avatar-ambiguous", "⚠️ 有多条待绑定记录头像过于相似，无法自动确认，请联系管理员手动绑定");
        DEFAULTS.put("group-not-bound", "⚠️ 您未绑定白名单，请先完成绑定");
        DEFAULTS.put("group-auth-unavailable", "⚠️ 登录服务暂不可用，请稍后再试或联系管理员");
        DEFAULTS.put("group-player-offline", "⚠️ 玩家 {player} 当前不在线，请先进入服务器再登录");
        DEFAULTS.put("group-already-logged-in", "ℹ️ 你已经登录过了，无需重复登录");
        DEFAULTS.put("group-bind-offline", "\n> ⏳ 你当前不在线，请重新进服后在群里发送「登录」开始游玩");
        // —— 群聊侧·管理命令（BindingAdminHandler）——
        DEFAULTS.put("admin-service-unavailable", "白名单未启用，或绑定库尚未就绪");
        DEFAULTS.put("admin-list-empty", "当前还没有任何白名单绑定");
        DEFAULTS.put("admin-list-truncated", "仅显示前 {limit} 条，共 {total} 条。用「查绑定 <玩家/QQ>」精确查询");
        DEFAULTS.put("admin-list-hint", "查单个：查绑定 <玩家/QQ>　·　解绑：解绑 <玩家>");
        DEFAULTS.put("admin-query-usage", "用法：查绑定 <玩家名 或 QQ号>");
        DEFAULTS.put("admin-query-not-found", "没找到该玩家名 / QQ 号的绑定");
        DEFAULTS.put("admin-unbind-usage", "用法：解绑 <玩家名>");
        DEFAULTS.put("admin-unbind-not-found", "该玩家没有绑定记录，无需解绑");
        DEFAULTS.put("admin-unbind-done", "该玩家下次进服需重新绑定白名单");
        DEFAULTS.put("admin-unbind-error", "删除记录时出错，请查看后台日志");
        // —— 群聊侧·登录卡片（AuthVelocityPlugin）——
        DEFAULTS.put("group-login-card-title", "## 🔐 登录请求\n");
        DEFAULTS.put("group-login-card-tip", "\n> Tips: 在群里回复「**{login}**」亦可登录 ✅\n");
        DEFAULTS.put("group-login-card-player", "👤 **玩家**　");
        // —— 群聊侧·管理命令卡片标题/字段标签 ——
        DEFAULTS.put("admin-card-title-unavailable", "绑定服务不可用");
        DEFAULTS.put("admin-card-title-list", "绑定列表");
        DEFAULTS.put("admin-card-title-query", "查绑定");
        DEFAULTS.put("admin-card-title-info", "绑定信息");
        DEFAULTS.put("admin-card-title-unbind", "解绑");
        DEFAULTS.put("admin-card-title-unbound", "已解绑");
        DEFAULTS.put("admin-card-title-unbind-fail", "解绑失败");
        DEFAULTS.put("admin-field-keyword", "关键词");
        DEFAULTS.put("admin-field-player", "玩家");
        DEFAULTS.put("admin-field-qq", "QQ");
        DEFAULTS.put("admin-field-openid", "openid");
        DEFAULTS.put("admin-field-bound-at", "绑定于");
        DEFAULTS.put("admin-field-original-qq", "原 QQ");
        DEFAULTS.put("admin-category", "👑 管理");
        DEFAULTS.put("admin-usage", "绑定列表 / 查绑定 <玩家|QQ> / 解绑 <玩家>");
        DEFAULTS.put("admin-desc", "白名单绑定管理（列表/查询/解绑）");
        DEFAULTS.put("admin-trigger-list", "绑定列表");
        DEFAULTS.put("admin-trigger-query", "查绑定");
        DEFAULTS.put("admin-trigger-unbind", "解绑");
        // —— 群聊侧·成功卡片默认模板（config 可覆盖）——
        DEFAULTS.put("card-bind-success",
                "## 🎉 绑定成功 · 欢迎加入！\n"
                + "👤 **玩家**　{player}\n"
                + "🐧 **QQ**　　{qq}\n"
                + "\n> 🌟 白名单已开通，进服开启你的冒险吧~");
        DEFAULTS.put("card-login-success",
                "## ✅ 登录成功 · 欢迎回来！\n"
                + "👤 **玩家**　{player}\n"
                + "\n> 🎮 一切就绪，祝你玩得开心！");
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
            v.put(e.getKey(), normalizeEscapes(m.get(e.getKey(), e.getValue())));
        }
        values = v;
    }

    /** 取文案（未知键返回空串）。 */
    public static String get(String key) {
        String v = values.get(key);
        return v != null ? normalizeEscapes(v) : "";
    }

    private static String normalizeEscapes(String value) {
        if (value == null || value.isEmpty()) return value;
        return value
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", "\t");
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

    /** 已记录QQ，请群里发绑定（{qq}=QQ号, {prompt}=绑定关键词, {group}=群号）。 */
    public static String qqRecorded(String qq, String prompt, String group) {
        return format("qq-recorded", "{qq}", qq, "{prompt}", prompt, "{group}", group);
    }

    /** 玩家不在线（{player}=玩家名）。 */
    public static String groupPlayerOffline(String player) {
        return format("group-player-offline", "{player}", player);
    }

    /** 列表截断提示（{limit}/{total}）。 */
    public static String adminListTruncated(int limit, int total) {
        return format("admin-list-truncated", "{limit}", String.valueOf(limit), "{total}", String.valueOf(total));
    }

    /** 管理命令触发词列表（[列表, 查绑定, 解绑]）。 */
    public static java.util.List<String> adminTriggers() {
        return java.util.Arrays.asList(get("admin-trigger-list"), get("admin-trigger-query"), get("admin-trigger-unbind"));
    }

    /** 成功卡片默认模板（绑定）。 */
    public static String cardBindSuccessDefault() { return get("card-bind-success"); }

    /** 成功卡片默认模板（登录）。 */
    public static String cardLoginSuccessDefault() { return get("card-login-success"); }
}
