package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.windy.xingtubot.common.module.capability.ServerQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link ServerQuery} 的 Velocity 实现：遍历各子服 + 在线玩家归属。
 * 供 module-admin 的查服命令使用，使其保持平台中立。
 */
public final class VelocityServerQuery implements ServerQuery {

    private final ProxyServer proxy;

    public VelocityServerQuery(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public List<ServerInfo> servers() {
        List<ServerInfo> result = new ArrayList<>();
        for (RegisteredServer reg : proxy.getAllServers()) {
            String serverName = reg.getServerInfo().getName();
            List<String> players = proxy.getAllPlayers().stream()
                    .filter(p -> p.getCurrentServer()
                            .map(conn -> conn.getServerInfo().getName().equals(serverName))
                            .orElse(false))
                    .map(Player::getUsername)
                    .collect(Collectors.toList());
            result.add(new ServerInfo(serverName, players));
        }
        return result;
    }
}
