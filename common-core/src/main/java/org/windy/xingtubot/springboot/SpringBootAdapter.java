package org.windy.xingtubot.springboot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.windy.xingtubot.common.platform.PlatformAdapter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Spring Boot 平台适配器：把 {@link PlatformAdapter} 的调度/日志/广播委托给
 * Spring 的线程池和 SLF4J。
 *
 * <p>广播和玩家消息在此模式下退化为日志输出（独立运行无游戏内玩家），
 * 若需要真正的消息推送，可通过 {@link XingtuBotSpringApplication} 注册的
 * {@code ProactiveSender} 走 QQ 群消息通道。
 */
public class SpringBootAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(SpringBootAdapter.class);

    private final ThreadPoolTaskScheduler scheduler;

    public SpringBootAdapter(ThreadPoolTaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void runAsync(Runnable r) {
        CompletableFuture.runAsync(r, scheduler.getScheduledExecutor());
    }

    @Override
    public void runSync(Runnable r) {
        // 独立运行无主线程概念，直接在调度线程执行
        scheduler.getScheduledExecutor().execute(r);
    }

    @Override
    public void log(String msg) {
        log.info(msg);
    }

    @Override
    public void broadcast(String msg) {
        // 独立运行无游戏内玩家，广播退化为日志
        log.info("[广播] {}", msg);
    }

    @Override
    public void sendMessageToPlayer(UUID uuid, String msg) {
        // 独立运行无游戏内玩家，退化为日志
        log.info("[玩家消息] {}: {}", uuid, msg);
    }
}
