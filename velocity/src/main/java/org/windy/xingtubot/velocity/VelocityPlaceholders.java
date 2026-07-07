package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.reply.PlaceholderResolver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Velocity 内置占位符解析。不依赖 PAPI、不依赖玩家在线，群场景可用：
 * <ul>
 *   <li>{online} 全服在线人数</li>
 *   <li>{sender} 发送者（已绑定显示玩家名，否则默认昵称）</li>
 *   <li>{date} 日期 {time} 时间</li>
 *   <li>{servers} 子服名列表 {server_count} 子服数</li>
 * </ul>
 *
 * <p>PAPI 的 %xxx%（需玩家上下文+跨服解析）为二期，暂保留原样不替换。
 */
public class VelocityPlaceholders implements PlaceholderResolver {

    private final ProxyServer proxy;
    private final BindingService binding; // 可为 null
    private final VelocityBridge bridge;  // 可为 null（PAPI 跨服解析用）
    private final String defaultSender;

    public VelocityPlaceholders(ProxyServer proxy, BindingService binding, VelocityBridge bridge,
                                String defaultSender) {
        this.proxy = proxy;
        this.binding = binding;
        this.bridge = bridge;
        this.defaultSender = defaultSender == null ? "群成员" : defaultSender;
    }

    @Override
    public void resolve(String text, BotMessageEvent event, java.util.function.Consumer<String> callback) {
        // ① 先同步替换内置占位符
        String builtin = resolveBuiltin(text, event);
        // ② 若还含 PAPI %xxx% 且发送者绑定+在线，跨服解析；否则直接回调
        if (bridge != null && builtin.contains("%")) {
            String player = boundPlayer(event);
            if (player != null) {
                bridge.resolvePapi(player, builtin, callback);
                return;
            }
        }
        callback.accept(builtin);
    }

    /** 发送者绑定的玩家名（未绑定返回 null）。 */
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
            r = r.replace("{online}", String.valueOf(proxy.getPlayerCount()));
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
            r = r.replace("{server_count}", String.valueOf(proxy.getAllServers().size()));
        }
        if (r.contains("{servers}")) {
            String list = proxy.getAllServers().stream()
                    .map(s -> s.getServerInfo().getName())
                    .collect(Collectors.joining("、"));
            r = r.replace("{servers}", list);
        }
        // {server_list} 各子服分行带人数 + 该服玩家名，适合 markdown 卡片
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
        // {player_names} 当前在线玩家名（逗号分隔，没人则“暂无”）
        if (r.contains("{player_names}")) {
            String names = proxy.getAllPlayers().stream()
                    .map(p -> p.getUsername())
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
