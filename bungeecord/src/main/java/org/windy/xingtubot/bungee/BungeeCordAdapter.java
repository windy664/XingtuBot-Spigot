package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public class BungeeCordAdapter implements PlatformAdapter {
    private final ProxyServer proxy;
    // adapter.log 是调试通道（原始事件 JSON 等），仅 debug=true 时输出；惰性读 config 支持 /reload 改动。
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
        proxy.getScheduler().runAsync(null, r); // BungeeCord 无主线程概念
    }

    @Override
    public void log(String msg) {
        // 仅调试模式输出（与 SpigotAdapter 一致）：debug=false 时不打原始事件 JSON 等调试日志。
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
