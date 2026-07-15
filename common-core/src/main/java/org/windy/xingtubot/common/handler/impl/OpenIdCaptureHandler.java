package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.handler.HandlerContext;
import org.windy.xingtubot.common.handler.BotMessageHandler;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * openid 捕获：开启后下一条群消息把发送者 openid 打印到控制台并自动关闭。
 * priority=0，最先匹配。
 */
public class OpenIdCaptureHandler implements BotMessageHandler {

    private final AtomicBoolean capture = new AtomicBoolean(false);
    private Consumer<String> consoleLogger;

    public void enableCapture() {
        capture.set(true);
    }

    @Override
    public boolean matches(String message, BotMessageContext event) {
        return capture.get();
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        capture.set(false);
        String openid = event.getSenderId();
        String who = message.trim();
        String info = "════════ openid 捕获 ════════\n"
                + "发送者说：" + who + "\n"
                + "openid = " + openid + "\n"
                + "把它加入 config.yml 的 admin-openids 即可设为超管\n"
                + "═══════════════════════════";
        if (consoleLogger != null) {
            consoleLogger.accept(info);
        }
        event.reply("✅ 已捕获你的 openid，请管理员查看后台控制台");
    }

    @Override
    public String name() {
        return "openid-capture";
    }

    @Override
    public int priority() {
        return 0;
    }

    /** 设置控制台输出回调（Velocity = ProxyServer.sendMessage，Spigot = Logger.info）。 */
    public void setConsoleLogger(Consumer<String> consoleLogger) {
        this.consoleLogger = consoleLogger;
    }
}
