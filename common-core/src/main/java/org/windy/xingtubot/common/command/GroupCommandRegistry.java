package org.windy.xingtubot.common.command;

import org.windy.xingtubot.common.handler.PermissionChecker;
import org.windy.xingtubot.common.event.BotMessageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 群指令注册与分发中心。
 *
 * <p>按注册顺序匹配，命中即处理并停止（先注册的优先）。
 *
 * <p>所有 handle 在<b>专属有界线程池</b>执行：与 Velocity 主线程、与 JDK 公共 ForkJoinPool 都隔离。
 * 即使图床/接口卡住或被刷爆，最多让群指令排队变慢，绝不波及代理核心或其它功能。
 */
public class GroupCommandRegistry {

    private final List<GroupCommand> commands = new ArrayList<>();
    private final Consumer<String> logger;
    private final PermissionChecker permission;
    private final ThreadPoolExecutor pool;

    public GroupCommandRegistry(PermissionChecker permission, Consumer<String> logger) {
        this.permission = permission;
        this.logger = logger;
        // 核心 2、最大 4 线程，队列 50；满了直接丢弃并提示，绝不阻塞调用方（轮询线程）
        this.pool = new ThreadPoolExecutor(
                2, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "GroupCmd-Worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    public GroupCommandRegistry register(GroupCommand cmd) {
        commands.add(cmd);
        return this;
    }

    public GroupCommandRegistry register(BotCommand cmd) {
        if (cmd == null) return this;
        return register(new BotCommandAdapter(cmd));
    }

    /**
     * 尝试分发一条群消息。命中返回 true（已提交到专属线程池异步处理），否则 false。
     */
    public boolean dispatch(BotMessageEvent event) {
        String msg = event.getMessage() == null ? "" : event.getMessage().trim();
        if (msg.isEmpty()) return false;

        for (GroupCommand cmd : commands) {
            boolean hit;
            try {
                hit = cmd.matches(msg);
            } catch (Exception e) {
                hit = false;
            }
            if (hit) {
                // 权限检查：管理类指令仅超管可用
                if (cmd.adminOnly() && !permission.isAdmin(event.getSenderId())) {
                    event.reply("⛔ 该指令仅管理员可用");
                    return true;
                }
                pool.execute(() -> {
                    try {
                        cmd.handle(msg, event);
                    } catch (Exception e) {
                        log("指令 " + cmd.name() + " 处理异常: " + e.getMessage());
                        try {
                            event.reply("处理出错了，稍后再试~");
                        } catch (Exception ignored) {
                        }
                    }
                });
                return true;
            }
        }
        return false;
    }

    /**
     * 生成帮助菜单（Markdown）。自动收集所有已注册、且声明了 usage 的指令。
     *
     * @param isAdmin 请求者是否超管（决定是否列出管理指令）
     */
    public String buildMenu(boolean isAdmin) {
        return buildMenu(isAdmin, "机器人");
    }

    public String buildMenu(boolean isAdmin, String botName) {
        StringBuilder normal = new StringBuilder();
        StringBuilder admin = new StringBuilder();
        for (GroupCommand cmd : commands) {
            String usage = cmd.usage();
            if (usage == null) continue; // 隐藏指令不进菜单
            // 命令名用行内代码块 + 全角空格 + 描述，竖看一目了然
            String line = "`" + usage + "`　" + cmd.description() + "\n";
            if (cmd.adminOnly()) {
                admin.append(line);
            } else {
                normal.append(line);
            }
        }
        StringBuilder sb = new StringBuilder("## 🤖 ").append(botName).append(" · 菜单\n");
        if (normal.length() > 0) sb.append('\n').append(normal);
        if (isAdmin && admin.length() > 0) {
            sb.append("\n> 👑 管理员专用\n\n").append(admin);
        }
        sb.append("\n> 💡 @我 + 上面任意指令即可使用");
        return sb.toString();
    }

    /** 是否有任意可显示的指令（用于判断菜单是否为空）。 */
    public boolean hasMenuEntries() {
        for (GroupCommand cmd : commands) {
            if (cmd.usage() != null) return true;
        }
        return false;
    }

    /** 关闭线程池（插件停用时调用）。 */
    public void shutdown() {
        pool.shutdownNow();
    }

    private void log(String m) {
        if (logger != null) logger.accept(m);
    }

    private static class BotCommandAdapter implements GroupCommand {
        private final BotCommand cmd;

        BotCommandAdapter(BotCommand cmd) {
            this.cmd = cmd;
        }

        @Override
        public boolean matches(String message) {
            return cmd.matches(message);
        }

        @Override
        public void handle(String message, BotMessageEvent event) {
            cmd.handle(message, event);
        }

        @Override
        public String name() {
            return cmd.name();
        }

        @Override
        public boolean adminOnly() {
            return cmd.adminOnly();
        }

        @Override
        public boolean adminFor(String message) {
            return cmd.adminFor(message);
        }

        @Override
        public java.util.List<String> triggers() {
            return cmd.triggers();
        }

        @Override
        public String usage() {
            return cmd.usage();
        }

        @Override
        public String description() {
            return cmd.description();
        }

        @Override
        public String category() {
            return cmd.category();
        }
    }
}
