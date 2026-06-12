package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class VelocityAdapter implements PlatformAdapter {
    private final ProxyServer proxy;

    public VelocityAdapter(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public void runAsync(Runnable r) {
        CompletableFuture.runAsync(r);
    }

    @Override
    public void runSync(Runnable r) {
        CompletableFuture.runAsync(r); // Velocity 没有主线程
    }

    @Override
    public void log(String msg) {
        proxy.getConsoleCommandSource().sendMessage(Component.text(msg));
    }

    @Override
    public void broadcast(String msg) {
        Component component = Component.text(msg);
        proxy.getAllPlayers().forEach(player -> player.sendMessage(component));
    }

    @Override
    public void sendMessageToPlayer(UUID uuid, String msg) {
        proxy.getPlayer(uuid).ifPresent(player -> player.sendMessage(Component.text(msg)));
    }
}
