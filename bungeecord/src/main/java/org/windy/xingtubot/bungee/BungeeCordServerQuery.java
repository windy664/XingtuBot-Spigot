package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.config.ServerInfo;
import org.windy.xingtubot.common.module.capability.ServerQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link ServerQuery} 的 BungeeCord 实现。
 */
public final class BungeeCordServerQuery implements ServerQuery {

    private final ProxyServer proxy;

    public BungeeCordServerQuery(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public List<ServerInfo> servers() {
        List<ServerInfo> result = new ArrayList<>();
        for (net.md_5.bungee.api.config.ServerInfo si : proxy.getServers().values()) {
            List<String> players = proxy.getPlayers().stream()
                    .filter(p -> p.getServer() != null
                            && p.getServer().getInfo().getName().equals(si.getName()))
                    .map(ProxiedPlayer::getName)
                    .collect(Collectors.toList());
            result.add(new ServerInfo(si.getName(), players));
        }
        return result;
    }
}
