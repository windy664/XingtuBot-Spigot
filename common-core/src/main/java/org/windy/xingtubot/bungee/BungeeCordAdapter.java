package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public class BungeeCordAdapter implements PlatformAdapter {
    private final ProxyServer proxy;


    private final BooleanSupplier debugEnabled;

    public BungeeCordAdapter(ProxyServer proxy, BooleanSupplier debugEnabled) {
        this.proxy = proxy;
        this.debugEnabled = debugEnabled != null ? debugEnabled : () -> false;
    }

    @Override
    public void runAsync(Runnable r) {
        proxy.getScheduler().runAsync(null, r);
    }

    @Override
    public void runSync(Runnable r) {
        proxy.getScheduler().runAsync(null, r);
    }

    @Override
    public void log(String msg) {

        if (debugEnabled.getAsBoolean()) {
            proxy.getLogger().info(msg);
        }
    }

    @Override
    public void broadcast(String msg) {
        TextComponent component = new TextComponent(msg);
        proxy.getPlayers().forEach(player -> player.sendMessage(component));
    }

    @Override
    public void sendMessageToPlayer(UUID uuid, String msg) {
        proxy.getPlayer(uuid).sendMessage(new TextComponent(msg));
    }
}
