package org.windy.xingtubot.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.checkerframework.checker.units.qual.A;
import org.windy.xingtubot.core.ai.AiService;
import org.windy.xingtubot.core.api.BotCore;
import org.windy.xingtubot.core.api.BotMessageEvent;
import org.windy.xingtubot.core.ws.StandardWebSocketClient;

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
        String apiKey = "sk-****************";
        String baseUrl = "https://api.deepseek.com/v1";
        AiService aiService = new AiService(apiKey, baseUrl);

        BotCommandHandler commandHandler = new BotCommandHandler(proxy, aiService);
        try {
            VelocityAdapter adapter = new VelocityAdapter(proxy);
            String wsUrl = "ws://127.0.0.1:8080"; // 推荐外部配置
            StandardWebSocketClient ws = new StandardWebSocketClient(wsUrl);
            ws.enableHeartbeat(30000);
            botCore = new BotCore(adapter, ws);


            // 注册消息监听器
            botCore.addMessageListener(event -> {
                adapter.log("[VC] 收到Bot消息: " + event.getMessage());
                commandHandler.handle(event);
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