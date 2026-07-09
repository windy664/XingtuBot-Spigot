package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BungeeCord 内置占位符解析。与 VelocityPlaceholders 功能对等。
 */
public class BungeeCordPlaceholders implements PlaceholderResolver {

    private final ProxyServer proxy;
    private final BindingService binding;
    private final BungeeCordBridge bridge;
    private final String defaultSender;

    public BungeeCordPlaceholders(ProxyServer proxy, BindingService binding, BungeeCordBridge bridge,
                                  String defaultSender) {
        this.proxy = proxy;
        this.binding = binding;
        this.bridge = bridge;
        this.defaultSender = defaultSender == null ? "群成员" : defaultSender;
    }

    @Override
    public void resolve(String text, BotMessageEvent event, java.util.function.Consumer<String> callback) {
        String builtin = resolveBuiltin(text, event);
        if (bridge != null && builtin.contains("%")) {
            String player = boundPlayer(event);
            if (player != null) {
                bridge.resolvePapi(player, builtin, callback);
                return;
            }
        }
        callback.accept(builtin);
    }

    private String boundPlayer(BotMessageEvent event) {
        if (binding != null && event.getFormId() != null) {
            List<String> players = binding.getStore().getPlayersByOpenid(event.getFormId());
            if (!players.isEmpty()) return players.get(0);
        }
        return null;
    }

    private String resolveBuiltin(String text, BotMessageEvent event) {
        if (text == null || text.isEmpty()) return text;
        String r = text;
        if (r.contains("{online}")) {
            r = r.replace("{online}", String.valueOf(proxy.getOnlineCount()));
        }
        if (r.contains("{sender}")) {
            r = r.replace("{sender}", senderName(event));
        }
        if (r.contains("{date}")) {
            r = r.replace("{date}", new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        }
        if (r.contains("{time}")) {
            r = r.replace("{time}", new SimpleDateFormat("HH:mm").format(new Date()));
        }
        if (r.contains("{server_count}")) {
            r = r.replace("{server_count}", String.valueOf(proxy.getServers().size()));
        }
        if (r.contains("{servers}")) {
            String list = proxy.getServers().keySet().stream()
                    .collect(Collectors.joining("、"));
            r = r.replace("{servers}", list);
        }
        if (r.contains("{server_list}")) {
            StringBuilder sb = new StringBuilder();
            for (ServerInfo si : proxy.getServers().values()) {
                long count = proxy.getPlayers().stream()
                        .filter(p -> p.getServer() != null && p.getServer().getInfo().getName().equals(si.getName()))
                        .count();
                sb.append("- **").append(si.getName()).append("** · ").append(count).append(" 人\n");
                String names = proxy.getPlayers().stream()
                        .filter(p -> p.getServer() != null && p.getServer().getInfo().getName().equals(si.getName()))
                        .map(net.md_5.bungee.api.connection.ProxiedPlayer::getName)
                        .collect(Collectors.joining("、"));
                if (!names.isEmpty()) sb.append("  └ ").append(names).append("\n");
            }
            r = r.replace("{server_list}", sb.toString().trim());
        }
        if (r.contains("{player_names}")) {
            String names = proxy.getPlayers().stream()
                    .map(net.md_5.bungee.api.connection.ProxiedPlayer::getName)
                    .collect(Collectors.joining("、"));
            r = r.replace("{player_names}", names.isEmpty() ? "暂无" : names);
        }
        return r;
    }

    private String senderName(BotMessageEvent event) {
        if (binding != null && event.getFormId() != null) {
            List<String> players = binding.getStore().getPlayersByOpenid(event.getFormId());
            if (!players.isEmpty()) return players.get(0);
        }
        if (event.getUsername() != null && !event.getUsername().isEmpty()) {
            return event.getUsername();
        }
        return defaultSender;
    }
}
