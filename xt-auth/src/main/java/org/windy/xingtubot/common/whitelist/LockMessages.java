package org.windy.xingtubot.common.whitelist;

/**
 * 三端共享的游戏内锁定期文案（真·可配置，非"i18n 假门面"）。
 *
 * <p>默认值即原硬编码简体中文；各平台在启动时调一次 {@link #load} 从独立的 {@code messages.yml}
 * 覆盖，服主可自行改措辞。单语言、扁平 key、无 locale 切换。
 *
 * <p>bossbar 会自动 strip 掉 §颜色码取纯文本（见 {@link LockBossBar}），故这里带 §码无妨。
 */
public final class LockMessages {

    // 默认值 = 迁移前的硬编码中文
    private static volatile String awaitQq   = "§6欢迎 · 请在聊天框输入你的 QQ号 开始白名单绑定";
    private static volatile String needBind  = "§e就差一步 · 手持地图用手机 QQ 扫码加群，并在群里发送「绑定」完成头像验证";
    private static volatile String needLogin = "§a欢迎回来 · 在群里点机器人发的「登录」按钮完成上线";
    private static volatile String qqInvalid = "§c请输入有效的 QQ 号（5-12 位数字）";
    private static volatile String notReady  = "§c绑定服务未就绪，请稍后再试";
    private static volatile String unlocked  = "§a✅ 已登录，祝游戏愉快！";
    private static volatile String qrFallback = "§e加入 QQ 群：{group} {url}";

    private LockMessages() {
    }

    /** messages.yml 的键→值查找（带默认值）。各平台用 {@code YamlBotConfig::getString} 适配。 */
    public interface Lookup {
        String get(String key, String def);
    }

    /** 平台启动时调一次，从 messages.yml 覆盖默认值（缺键则保留默认）。 */
    public static void load(Lookup m) {
        if (m == null) return;
        awaitQq    = m.get("await-qq", awaitQq);
        needBind   = m.get("need-bind", needBind);
        needLogin  = m.get("need-login", needLogin);
        qqInvalid  = m.get("qq-invalid", qqInvalid);
        notReady   = m.get("not-ready", notReady);
        unlocked   = m.get("unlocked", unlocked);
        qrFallback = m.get("qr-fallback", qrFallback);
    }

    public static String awaitQq()   { return awaitQq; }
    public static String needBind()  { return needBind; }
    public static String needLogin() { return needLogin; }
    public static String qqInvalid() { return qqInvalid; }
    public static String notReady()  { return notReady; }
    public static String unlocked()  { return unlocked; }

    /** {group}/{url} 占位符替换后的加群兜底文案（packetevents 不可用时用）。 */
    public static String qrFallback(String group, String url) {
        return qrFallback
                .replace("{group}", group == null ? "" : group)
                .replace("{url}", url == null ? "" : url);
    }
}
