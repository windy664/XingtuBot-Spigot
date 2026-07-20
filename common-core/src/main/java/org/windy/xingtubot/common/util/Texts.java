package org.windy.xingtubot.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的启动 logo、状态栏格式化工具。
 * 三个平台（Bukkit / Velocity / BungeeCord）共用，避免重复硬编码。
 */
public final class Texts {

    private Texts() {}

    // ==================== ASCII Art Logo ====================

    private static final String[] LOGO = {
        " ██╗  ██╗██╗███╗   ██╗ ██████╗ ████████╗██╗   ██╗██████╗  ██████╗ ████████╗",
        " ╚██╗██╔╝██║████╗  ██║██╔════╝ ╚══██╔══╝██║   ██║██╔══██╗██╔═══██╗╚══██╔══╝",
        "  ╚███╔╝ ██║██╔██╗ ██║██║        ██║   ██║   ██║██████╔╝██║   ██║   ██║   ",
        "  ██╔██╗ ██║██║╚██╗██║██║        ██║   ██║   ██║██╔══██╗██║   ██║   ██║   ",
        " ██╔╝ ██╗██║██║ ╚████║╚██████╗   ██║   ╚██████╔╝██████╔╝╚██████╔╝   ██║   ",
        " ╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝ ╚═════╝   ╚═╝    ╚═════╝ ╚═════╝  ╚═════╝    ╚═╝   "
    };

    /** 获取 logo 行（供平台侧自行打印）。 */
    public static String[] logoLines() {
        return LOGO.clone();
    }

    // ==================== 启动 Banner ====================

    /**
     * 构建完整启动 banner（logo + 版本 + 已启动提示）。
     *
     * @param version   版本号
     * @param platform  平台标识（如 "Velocity" / "Bukkit" / "BungeeCord"）
     * @param helpCmd   帮助命令（如 "/vxtb help"）
     * @return 全部行（含前后空行）
     */
    public static List<String> banner(String version, String platform, String helpCmd) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        for (String l : LOGO) lines.add(l);
        lines.add("");
        lines.add("▌ 昕途机器人 · " + platform + " v" + version);
        lines.add("▌ ✔ 已启动  输入 " + helpCmd + " 查看命令");
        lines.add("");
        return lines;
    }

    /**
     * 构建启动 banner（自动检测版本，适合 Bukkit/BungeeCord 用 getDescription().getVersion()）。
     */
    public static List<String> banner(String version, String platform, String helpCmd, String roleInfo) {
        List<String> lines = banner(version, platform, helpCmd);
        // 在 "已启动" 行前插入角色信息
        if (roleInfo != null && !roleInfo.isEmpty()) {
            lines.add(lines.size() - 2, "▌ 角色     " + roleInfo);
        }
        return lines;
    }

    // ==================== 状态栏 ====================

    /**
     * 构建状态块（供 /xtb status 等命令使用）。
     *
     * @param title  标题（如 "运行状态"）
     * @param items  键值对：{label, value, label, value, ...}
     * @return 全部行
     */
    public static List<String> statusBlock(String title, String... items) {
        List<String> lines = new ArrayList<>();
        lines.add("▌ 昕途机器人 · " + title);
        lines.add("");
        for (int i = 0; i + 1 < items.length; i += 2) {
            lines.add(statusLine(items[i], items[i + 1]));
        }
        return lines;
    }

    /**
     * 格式化一行状态（▌ 前缀 + 等宽标签 + 值）。
     * 标签按显示宽度 pad 到 8 格（适配 4 个中文或 8 个英文字符）。
     */
    public static String statusLine(String label, String value) {
        return "▌ " + Pretty.padEnd(label, 8) + " " + value;
    }
}
