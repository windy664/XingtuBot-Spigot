package org.windy.xingtubot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.windy.xingtubot.core.api.BotCore;
import org.windy.xingtubot.core.api.BotMessageEvent;
import org.windy.xingtubot.core.ws.StandardWebSocketClient;

import java.util.function.Consumer;

@Plugin(
        id = "xingtubotvelocity",
        name = "XingtuBotVelocity",
        version = "1.0",
        authors = {"你的名字"}
)
public class XingtuBotVelocity {
    private final ProxyServer proxy;
    private BotCore botCore;

    @Inject
    public XingtuBotVelocity(ProxyServer proxy) {
        this.proxy = proxy;
        init();
    }

    private void init() {
        try {
            VelocityAdapter adapter = new VelocityAdapter(proxy);
            String wsUrl = "ws://127.0.0.1:8080";
            StandardWebSocketClient ws = new StandardWebSocketClient(wsUrl);
            ws.enableHeartbeat(30000);
            botCore = new BotCore(adapter, ws);

            // 注册消息监听器
            botCore.addMessageListener(event -> {
                adapter.log("收到Bot消息: " + event.getMessage());
                // 业务处理，例如自动回复
                if ("ping".equalsIgnoreCase(event.getMessage())) {
                    event.reply("pong!");
                }
                // 广播到服务器
                adapter.broadcast("机器人消息: " + event.getMessage());
            });

            botCore.start();
            adapter.log("XingtuBotVelocity 已启动，WebSocket正在连接...");
        } catch (Exception e) {
            proxy.getConsoleCommandSource().sendMessage(
                    net.kyori.adventure.text.Component.text("[XingtuBot] WebSocket连接失败: " + e.getMessage())
            );
        }
    }
}