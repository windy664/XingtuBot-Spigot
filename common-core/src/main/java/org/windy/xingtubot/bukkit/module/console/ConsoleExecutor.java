package org.windy.xingtubot.bukkit.module.console;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 以控制台身份执行命令，并捕获输出（回传群）。
 *
 * <p>双通道捕获：同时挂 Logger Handler + 劫持 System.out，确保无论插件用
 * Logger 还是 System.println 都能抓到输出。
 *
 * <p>命令在主线程执行（Bukkit 要求），结果通过回调返回。
 */
public final class ConsoleExecutor {

    private ConsoleExecutor() {
    }

    /**
     * 主线程执行 command，捕获输出，通过 callback 返回（多行用 \n 连接）。
     *
     * <p>三通道捕获：
     * <ol>
     *   <li>CapturingConsoleSender（伪 RCON）：拦截 sender.sendMessage()，覆盖 say/broadcast 等命令</li>
     *   <li>Logger Handler：捕获 Logger.info() 输出</li>
     *   <li>System.out 劫持：捕获 System.println() 输出</li>
     * </ol>
     */
    public static void execute(Plugin plugin, String command, java.util.function.Consumer<String> callback) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<String> lines = new ArrayList<>();

            // 通道 1：CapturingConsoleSender（伪 RCON）—— 拦截 sender.sendMessage()
            CapturingConsoleSender capturing = new CapturingConsoleSender(msg -> {
                if (msg != null && !msg.trim().isEmpty()) lines.add(stripColor(msg));
            });

            // 通道 2：Logger Handler
            Logger root = Logger.getLogger("");
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record != null && record.getLevel().intValue() >= Level.INFO.intValue()) {
                        String msg = record.getMessage();
                        if (msg != null && !msg.trim().isEmpty()) lines.add(stripColor(msg));
                    }
                }
                @Override public void flush() { }
                @Override public void close() { }
            };

            // 通道 3：劫持 System.out
            java.io.PrintStream oldOut = System.out;
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            java.io.PrintStream captureOut = new java.io.PrintStream(captured);

            root.addHandler(handler);
            System.setOut(captureOut);

            boolean dispatched;
            try {
                // 用 CapturingConsoleSender 替代真实 ConsoleSender，拦截 sendMessage() 输出
                dispatched = Bukkit.dispatchCommand(capturing, command);
            } catch (Exception e) {
                root.removeHandler(handler);
                System.setOut(oldOut);
                callback.accept("❌ 执行异常: " + e.getMessage());
                return;
            }

            // 延迟清理：等异步输出到达（LiteSignIn 等插件可能在下一个 tick 才输出）
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                System.setOut(oldOut);
                root.removeHandler(handler);
                captureOut.flush();

                // flush CapturingConsoleSender 的缓冲
                capturing.flush();

                // 合并 System.out 捕获的输出
                String outStr = captured.toString().trim();
                if (!outStr.isEmpty()) {
                    for (String line : outStr.split("\n")) {
                        String trimmed = stripColor(line.trim());
                        if (!trimmed.isEmpty()) lines.add(trimmed);
                    }
                }

                StringBuilder sb = new StringBuilder();
                if (!lines.isEmpty()) {
                    int n = Math.min(lines.size(), 15);
                    for (int i = 0; i < n; i++) {
                        if (i > 0) sb.append("\n");
                        sb.append(lines.get(i));
                    }
                    if (lines.size() > n) sb.append("\n…（输出过长已截断）");
                } else {
                    sb.append("✅ 已执行: ").append(command);
                }
                callback.accept(sb.toString().trim());
            }, 5L); // 等 5 tick（250ms）
        });
    }

    private static String stripColor(String s) {
        return s.replaceAll("(?i)[§&][0-9A-FK-OR]", "").trim();
    }
}
