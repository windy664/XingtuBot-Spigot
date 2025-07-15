package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import org.windy.xingtubot.core.api.PlatformAdapter;

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
        CompletableFuture.runAsync(r); // Velocity没有主线程
    }

    @Override
    public void log(String msg) {
        proxy.getConsoleCommandSource().sendMessage(net.kyori.adventure.text.Component.text(msg));
    }

    @Override
    public void broadcast(String msg) {
        net.kyori.adventure.text.Component component = net.kyori.adventure.text.Component.text(msg);
        proxy.getAllPlayers().forEach(player -> player.sendMessage(component));
    }

    @Override
    public void sendMessageToPlayer(UUID uuid, String msg) {
        proxy.getPlayer(uuid).ifPresent(player ->
                player.sendMessage(net.kyori.adventure.text.Component.text(msg))
        );
    }
}