package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.auth.PermissionService;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.command.GroupCommandRegistry;
import org.windy.xingtubot.common.command.impl.AnimePicCommand;
import org.windy.xingtubot.common.command.impl.BindingListCommand;
import org.windy.xingtubot.common.command.impl.FortuneCommand;
import org.windy.xingtubot.common.command.impl.QueryBindingCommand;
import org.windy.xingtubot.common.command.impl.UnbindCommand;
import org.windy.xingtubot.common.command.impl.WeatherCommand;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.demo.RichReplyDemo;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.service.McmodApiService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Bot 指令处理器：解析群消息中的命令（AI / 查服 / MCMOD 搜索）。
 */
public class BotCommandHandler {
    private final ProxyServer proxy;
    private final AiService aiService;
    private final McmodApiService mcmodService;
    private final BotConfig config;
    private final BindingService bindingService; // 可为 null（未启用白名单大脑时）
    private final GroupCommandRegistry commands;
    private final PermissionService permission;

    public BotCommandHandler(ProxyServer proxy, AiService aiService, McmodApiService mcmodService,
                             BotConfig config, BindingService bindingService) {
        this.proxy = proxy;
        this.aiService = aiService;
        this.mcmodService = mcmodService;
        this.config = config;
        this.bindingService = bindingService;

        // 超管权限（按 openid）
        this.permission = new PermissionService(config.getStringList("admin-openids"));
        // 群指令工具集（天气/运势/随机图片…），专属有界线程池执行，与主线程/公共池隔离
        this.commands = new GroupCommandRegistry(permission,
                m -> proxy.getConsoleCommandSource().sendMessage(Component.text("[群指令] " + m)))
                .register(new WeatherCommand())
                .register(new FortuneCommand())
                .register(new AnimePicCommand(config.getString("webhook-relay-url", "")));

        // 管理指令（依赖绑定库，仅在白名单大脑启用时注册）
        if (bindingService != null) {
            commands.register(new UnbindCommand(bindingService.getStore()))
                    .register(new QueryBindingCommand(bindingService.getStore()))
                    .register(new BindingListCommand(bindingService.getStore()));
        }
    }

    // 后台 openid 捕获模式：开启后，下一条群消息把发送者 openid 打印到控制台并自动关闭
    private final java.util.concurrent.atomic.AtomicBoolean captureOpenid =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 由后台命令调用：开启一次性 openid 捕获。 */
    public void startCaptureOpenid() {
        captureOpenid.set(true);
    }

    public void handle(BotMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.trim().isEmpty()) return;

        // ==================== openid 捕获（后台 /xtb captureid 触发）====================
        if (captureOpenid.compareAndSet(true, false)) {
            String openid = event.getFormId();
            String who = msg.trim();
            proxy.getConsoleCommandSource().sendMessage(Component.text(
                    "════════ openid 捕获 ════════\n"
                    + "发送者说：" + who + "\n"
                    + "openid = " + openid + "\n"
                    + "把它加入 config.yml 的 admin-openids 即可设为超管\n"
                    + "═══════════════════════════"));
            event.reply("✅ 已捕获你的 openid，请管理员查看后台控制台");
            return;
        }

        // ==================== 白名单 / 登录（Velocity 主导，跨服执行 AuthMe）====================
        if (bindingService != null) {
            String trimmed = msg.trim();
            String openid = event.getFormId();
            String loginPrompt = config.getString("login-prompt", "登录");
            if (loginPrompt.equals(trimmed)) {
                CompletableFuture.runAsync(() -> event.reply(bindingService.loginByGroup(openid).message));
                return;
            }
            if (trimmed.contains("白名单")) {
                CompletableFuture.runAsync(() -> event.reply(bindingService.bindByGroupAvatar(openid).message));
                return;
            }
        }

        // ==================== 帮助菜单 ====================
        String t = msg.trim();
        if (t.equals("菜单") || t.equals("帮助") || t.equalsIgnoreCase("help")) {
            boolean isAdmin = permission.isAdmin(event.getFormId());
            if (commands.hasMenuEntries()) {
                event.replyMarkdown(commands.buildMenu(isAdmin), null);
            } else {
                event.reply("暂无可用指令");
            }
            return;
        }

        // ==================== 群指令工具集（天气/运势/随机图片）====================
        if (commands.dispatch(event)) {
            return;
        }

        // ==================== 富消息 demo（发「测试」触发）====================
        if (RichReplyDemo.maybeHandle(event, config)) {
            return;
        }

        // ==================== AI 聊天 ====================
        if (msg.startsWith("ai ")) {
            String userMsg = msg.substring(3).trim();
            proxy.getConsoleCommandSource().sendMessage(Component.text("[Bot] AI请求: " + userMsg));
            CompletableFuture.runAsync(() -> {
                try {
                    event.reply(aiService.chat(userMsg));
                } catch (Exception e) {
                    event.reply("AI请求异常: " + e.getMessage());
                }
            });
            return;
        }

        // ==================== 查服 ====================
        if ("/查服".equalsIgnoreCase(msg.trim())) {
            AtomicInteger total = new AtomicInteger(0);
            StringBuilder sb = new StringBuilder();
            sb.append("【服务器在线情况】\n");
            sb.append("-----------------------\n");

            for (RegisteredServer reg : proxy.getAllServers()) {
                String serverName = reg.getServerInfo().getName();
                List<Player> playersOnServer = proxy.getAllPlayers().stream()
                        .filter(player -> player.getCurrentServer()
                                .map(conn -> conn.getServerInfo().getName().equals(serverName))
                                .orElse(false))
                        .collect(Collectors.toList());

                int count = playersOnServer.size();
                total.addAndGet(count);
                sb.append(serverName).append(" (").append(count).append("人)\n");

                if (!playersOnServer.isEmpty()) {
                    String players = playersOnServer.stream()
                            .map(Player::getUsername)
                            .collect(Collectors.joining(", "));
                    sb.append("  └─ ").append(players).append("\n");
                }
            }

            sb.append("-----------------------\n");
            sb.append("全服总人数: ").append(total.get()).append("人");
            event.reply(sb.toString());
            return;
        }

        // ==================== MCMOD 搜索 ====================
        if (msg.toLowerCase().startsWith("/mod ") || msg.matches("\\d+")) {
            CompletableFuture.runAsync(() -> mcmodService.handleMessage(event));
        }
    }
}
