package org.windy.xingtubot.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public class VelocityAdapter implements PlatformAdapter {
    private final ProxyServer proxy;

    private final BooleanSupplier debugEnabled;

    public VelocityAdapter(ProxyServer proxy, BooleanSupplier debugEnabled) {
        this.proxy = proxy;
        this.debugEnabled = debugEnabled != null ? debugEnabled : () -> false;
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
        if (debugEnabled.getAsBoolean()) {
            proxy.getConsoleCommandSource().sendMessage(Component.text(msg));
        }
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
