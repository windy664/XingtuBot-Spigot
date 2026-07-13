package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * Velocity 内置占位符解析。不依赖 PAPI、不依赖玩家在线，群场景可用。
 *
 * <p>绑定相关占位符（{sender}）由 xt-auth 经服务总线反射解析，本类不直接依赖 binding 类型。
 */
public class VelocityPlaceholders implements PlaceholderResolver {

    private final ProxyServer proxy;
    private final VelocityBridge bridge;  // 可为 null（PAPI 跨服解析用）
    private final String defaultSender;

    public VelocityPlaceholders(ProxyServer proxy, VelocityBridge bridge, String defaultSender) {
        this.proxy = proxy;
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

    /** 发送者绑定的玩家名——绑定解析由 xt-auth 的 PlaceholderResolver 处理，此处只用 event username。 */
    private String boundPlayer(BotMessageEvent event) {
        return null; // xt-auth 注册的 PlaceholderResolver 会覆盖 {sender}
    }

    private String resolveBuiltin(String text, BotMessageEvent event) {
        if (text == null || text.isEmpty()) return text;
        String r = text;
        if (r.contains("{online}")) {
            r = r.replace("{online}", String.valueOf(proxy.getPlayerCount()));
        }
        if (r.contains("{sender}")) {
            String player = boundPlayer(event);
            r = r.replace("{sender}", player != null ? player :
                    (event.getUsername() != null && !event.getUsername().isEmpty()
                            ? event.getUsername() : defaultSender));
        }
        if (r.contains("{date}")) {
            r = r.replace("{date}", new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        }
        if (r.contains("{time}")) {
            r = r.replace("{time}", new SimpleDateFormat("HH:mm").format(new Date()));
        }
        if (r.contains("{server_count}")) {
            r = r.replace("{server_count}", String.valueOf(proxy.getAllServers().size()));
        }
        if (r.contains("{servers}")) {
            String list = proxy.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .collect(Collectors.joining("、"));
            r = r.replace("{servers}", list);
        }
        if (r.contains("{server_list}")) {
            StringBuilder sb = new StringBuilder();
            for (com.velocitypowered.api.proxy.server.RegisteredServer s : proxy.getAllServers()) {
                java.util.Collection<com.velocitypowered.api.proxy.Player> ps = s.getPlayersConnected();
                sb.append("- **").append(s.getServerInfo().getName())
                        .append("** · ").append(ps.size()).append(" 人\n");
                if (!ps.isEmpty()) {
                    String names = ps.stream().map(p -> p.getUsername())
                            .collect(Collectors.joining("、"));
                    sb.append("  └ ").append(names).append("\n");
                }
            }
            r = r.replace("{server_list}", sb.toString().trim());
        }
        if (r.contains("{player_names}")) {
            String names = proxy.getAllPlayers().stream()
                    .map(p -> p.getUsername())
                    .collect(Collectors.joining("、"));
            r = r.replace("{player_names}", names.isEmpty() ? "暂无" : names);
        }
        return r;
    }
}
