package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.platform.BotLogger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 专属线程池。
 *
 * <h3>为什么不共用 HandlerRegistry 的线程池</h3>
 * {@code HandlerRegistry} 的池是 2~6 线程 + 100 队列 + {@code DiscardPolicy}（静默丢弃）。
 * AI 的一次 LLM 调用读超时可达 25 秒，群里一热闹就会把这个池占满，
 * 结果<b>被静默丢掉的不只是 AI 的回复，还有其他模块的命令</b>。
 *
 * <p>所以 AI 走自己的池：队列满时只影响 AI 自己（降级为"这次不回复"），
 * 绝不拖累别的模块。
 *
 * <h3>为什么必须有它</h3>
 * QQ 网关的 WebSocket 线程负责解析入站消息。judge（要不要插话）需要发网络请求，
 * 如果在 {@code matches()} 里同步调用，一次卡顿就会把整条入站消息链堵住。
 * 有了这个池，{@code matches()} 只做纯计算，LLM 调用全部在这里面跑。
 */
public final class AiExecutor {

    private final ThreadPoolExecutor pool;
    private final BotLogger logger;

    public AiExecutor(BotLogger logger) {
        this.logger = logger;
        this.pool = new ThreadPoolExecutor(
                2, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "AiWorker");
                    t.setDaemon(true);
                    return t;
                },
                // AbortPolicy：抛异常让调用方知道"这次没排上"，从而降级而不是静默消失
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交一个 AI 任务。
     *
     * @return true 已入队；false 队列已满（调用方应降级为"这次不回复"）
     */
    public boolean submit(Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            if (logger != null) {
                logger.warn("[AI] AI 线程池队列已满，本次请求已丢弃（不影响其他模块）");
            }
            return false;
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
