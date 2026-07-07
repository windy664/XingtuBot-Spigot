package org.windy.xingtubot.common.redis;

import org.windy.xingtubot.common.platform.BotLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis 连接管理器：连接池 + Pub/Sub。
 *
 * <p>由主插件在启动时初始化（如果配置了 redis-host），通过服务总线或静态持有供各模块使用。
 * 预留扩展用途（缓存、消息队列等）。
 */
public class RedisManager {

    private final JedisPool pool;
    private final BotLogger logger;

    public RedisManager(String host, int port, String password, BotLogger logger) {
        this.logger = logger;
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);

        if (password != null && !password.isEmpty()) {
            pool = new JedisPool(poolConfig, host, port, 5000, password);
        } else {
            pool = new JedisPool(poolConfig, host, port, 5000);
        }
        logger.info("[Redis] 连接池已创建 (" + host + ":" + port + ")");
    }

    /** 获取一个 Jedis 连接（用完必须 close/归还）。 */
    public Jedis jedis() {
        return pool.getResource();
    }

    /** 发布消息到指定 channel。 */
    public void publish(String channel, String message) {
        try (Jedis j = jedis()) {
            j.publish(channel, message);
        } catch (Exception e) {
            logger.warn("[Redis] publish 失败: " + e.getMessage());
        }
    }

    /**
     * 订阅 channel（阻塞，需在独立线程调用）。
     * 断开后不会自动重连——调用方应自行 schedule 重连。
     */
    public void subscribe(JedisPubSub pubSub, String... channels) {
        try (Jedis j = jedis()) {
            j.subscribe(pubSub, channels);
        } catch (Exception e) {
            logger.warn("[Redis] subscribe 断开: " + e.getMessage());
        }
    }

    /** 关闭连接池。 */
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            logger.info("[Redis] 连接池已关闭");
        }
    }

    public boolean isAvailable() {
        try (Jedis j = jedis()) {
            j.ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
