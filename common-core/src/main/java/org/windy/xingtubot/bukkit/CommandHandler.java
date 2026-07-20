package org.windy.xingtubot.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.windy.xingtubot.bukkit.event.GuildMessageEvent;
import org.windy.xingtubot.common.qq.QqOpenApiClient;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.util.Texts;
import org.windy.xingtubot.common.runtime.BotRuntimeState;
import org.windy.xingtubot.common.module.XingtuBotHost;
import org.windy.xingtubot.bukkit.util.ProxyDetector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList(
            "reload", "connect", "reply", "status", "list", "debug");

    private final XingtuBot plugin;

    public CommandHandler(XingtuBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e用法: /xtb <子命令>");
            sender.sendMessage("§7  reload     - 重新加载配置");
            sender.sendMessage("§7  connect    - 通信模式说明");
            sender.sendMessage("§7  reply      - 回复最后一条群消息");
            sender.sendMessage("§7  status     - 运行状态");
            sender.sendMessage("§7  list       - 绑定列表");
            sender.sendMessage("§7  debug      - 切换调试模式");
            sender.sendMessage("§7  proactive <群id> [消息] - 测试主动消息");
            sender.sendMessage("§7  status  - 查看运行状态");
            sender.sendMessage("§7  list    - 查看绑定列表");
            sender.sendMessage("§7  debug   - 切换调试模式");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(sender);
                return true;

            case "connect":
                handleConnect(sender);
                return true;

            case "reply":
                handleReply(sender, args);
                return true;

            case "status":
                handleStatus(sender);
                return true;

            case "list":
                handleList(sender);
                return true;

            case "debug":
                handleDebug(sender);
                return true;

            case "proactive":
                handleProactive(sender, args);
                return true;

            default:
                sender.sendMessage("§c未知子命令: " + args[0] + "，使用 /xtb 查看帮助");
                sender.sendMessage("§c未知子命令: " + args[0] + "，使用 /xtb 查看帮助");
                return true;
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();

        List<String> reloaded = new ArrayList<>();

        sender.sendMessage("§a主插件配置已重新加载，已刷新: §f" + String.join(", ", reloaded));
        sender.sendMessage("§7附属扩展插件（xt-*）各自独立配置，重启服务器生效");
        sender.sendMessage("§7以下配置需重启服务器才生效: openapi-app-id 等");
    }

    private void handleConnect(CommandSender sender) {
        sender.sendMessage("§7部署角色自动探测：检测到代理 → 手脚（代理接管），否则 → 本地大脑。");
        sender.sendMessage("§7设置 server-role=off 可关闭本服 bot，修改后需重启服务器。");
    }

    private void handleReply(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /xtb reply <消息>");
            return;
        }
        String content = String.join(" ", args).substring(args[0].length() + 1);
        GuildMessageEvent lastEvent = plugin.getLastEvent();
        if (lastEvent == null) {
            sender.sendMessage("§c当前没有可以回复的事件。");
            return;
        }
        lastEvent.reply(content);
        sender.sendMessage("§a已发送回复: " + content);
    }

    private void handleStatus(CommandSender sender) {
        String boundCount = "—";
        Object store = getBindingStore();
        if (store != null) {
            try {
                @SuppressWarnings("unchecked")
                java.util.List<?> all = (java.util.List<?>) store.getClass()
                        .getMethod("all").invoke(store);
                boundCount = String.valueOf(all != null ? all.size() : 0);
            } catch (Exception ignored) {}
        }

        boolean behindProxy = ProxyDetector.isBehindProxy(plugin);
        String role = behindProxy ? "手脚（代理接管）" : "本地大脑";

        for (String line : Texts.statusBlock("运行状态",
                "部署角色", role,
                "在线玩家", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(),
                "已绑定", boundCount + " 人",
                "机器人名", org.windy.xingtubot.common.runtime.XingtuBotServiceImpl.runtime().getBotName())) {
            sender.sendMessage(line);
        }
    }

    private void handleList(CommandSender sender) {
        Object store = getBindingStore();
        if (store == null) {
            sender.sendMessage("§c绑定数据不可用（白名单未启用或为 slave 模式）。");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.List<?> all = (java.util.List<?>) store.getClass()
                    .getMethod("all").invoke(store);
            if (all == null || all.isEmpty()) {
                sender.sendMessage("§7暂无绑定记录。");
                return;
            }
            sender.sendMessage("§e绑定列表（" + all.size() + " 人）:");
            java.lang.reflect.Field fPlayer = null;
            java.lang.reflect.Field fOpenid = null;
            int i = 0;
            for (Object entry : all) {
                i++;
                if (fPlayer == null) {
                    fPlayer = entry.getClass().getField("player");
                    fOpenid = entry.getClass().getField("openid");
                }
                String player = (String) fPlayer.get(entry);
                String openid = (String) fOpenid.get(entry);
                boolean online = Bukkit.getPlayerExact(player) != null;
                String status = online ? "§a●" : "§7○";
                sender.sendMessage(status + " §f" + player + " §7- " + openid);
                if (i >= 50) {
                    sender.sendMessage("§7... 仅显示前 50 条");
                    break;
                }
            }
        } catch (Exception e) {
            sender.sendMessage("§c获取绑定列表失败: " + e.getMessage());
        }
    }

    private void handleDebug(CommandSender sender) {
        boolean current = org.windy.xingtubot.common.runtime.XingtuBotServiceImpl.runtime().isDebugEnabled();
        plugin.getConfig().set("debug", !current);
        plugin.saveConfig();
        sender.sendMessage("§a调试模式已" + (!current ? "§c开启" : "§a关闭") + "§a。");
    }

    private void handleProactive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /xtb proactive <群openid> [消息内容]");
            sender.sendMessage("§7测试主动消息推送（不依赖被动回复窗口）");
            return;
        }
        String groupOpenid = args[1];
        String content = args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : "这是一条主动消息测试";

        String appId = plugin.getConfig().getString("openapi-app-id", "").trim();
        String secret = plugin.getConfig().getString("openapi-client-secret", "").trim();
        if (appId.isEmpty() || secret.isEmpty()) {
            sender.sendMessage("§c未配置 openapi-app-id / openapi-client-secret");
            return;
        }
        boolean sandbox = plugin.getConfig().getBoolean("openapi-sandbox", false);
        QqOpenApiClient api =
                new QqOpenApiClient(appId, secret,
                        sandbox ? QqOpenApiClient.API_SANDBOX
                                : QqOpenApiClient.API_PROD, null);

        final String msg = "🔔 [主动消息] " + content;
        sender.sendMessage("§e正在发送主动消息到群 " + groupOpenid + "...");

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                api.sendProactiveGroupMessage(groupOpenid, msg);
                sender.sendMessage("§a✅ 主动消息发送成功！");
            } catch (Exception e) {
                // 主动消息失败 → 回退到被动队列
                PendingMessageQueue.getInstance()
                        .offer(groupOpenid, msg);
                sender.sendMessage("§e⚠️ 主动消息失败（无权限），已回退到被动队列: " + e.getMessage());
                sender.sendMessage("§7下次群里有人 @机器人 时会一起发出");
            }
        });
    }

    /** 绑定库由 xt-auth 注册到服务总线，通过 Class.forName 反射获取类型，避免编译期依赖 xt-auth。 */
    private Object getBindingStore() {
        try {
            XingtuBotHost host =
                    Bukkit.getServicesManager().load(XingtuBotHost.class);
            if (host == null) return null;
            Class<?> repoClass = Class.forName("org.windy.xingtubot.common.binding.BindingRepository");
            return host.getService(repoClass);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
