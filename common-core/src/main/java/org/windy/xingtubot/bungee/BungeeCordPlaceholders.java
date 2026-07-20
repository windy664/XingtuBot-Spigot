package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * BungeeCord 内置占位符解析。与 VelocityPlaceholders 功能对等。
 * 绑定解析由 xt-auth 的 PlaceholderResolver 处理。
 */
public class BungeeCordPlaceholders implements PlaceholderResolver {

    private final ProxyServer proxy;
    private final BungeeCordBridge bridge;

    public BungeeCordPlaceholders(ProxyServer proxy, BungeeCordBridge bridge) {
        this.proxy = proxy;
        this.bridge = bridge;
    }

    @Override
    public void resolve(String text, BotMessageContext event, java.util.function.Consumer<String> callback) {
        String builtin = resolveBuiltin(text, event);
        if (bridge != null && builtin.contains("%")) {
            bridge.resolvePapi(event.getUsername(), builtin, callback);
            return;
        }
        callback.accept(builtin);
    }

    private String resolveBuiltin(String text, BotMessageContext event) {
        if (text == null || text.isEmpty()) return text;
        String r = text;
        if (r.contains("{online}")) {
            r = r.replace("{online}", String.valueOf(proxy.getOnlineCount()));
        }
        if (r.contains("{sender}")) {
            r = r.replace("{sender}", event.getUsername());
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
}
