package org.windy.xingtubot.common.bridge;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.redis.RedisManager;

/**
 * 跨服信道工厂：按【核心框架】配置创建 {@link CrossServerChannel}（目前仅 Redis 实现）。
 *
 * <p>跨服信道是通用基础设施（PAPI/控制台/群服互联/白名单都经它跨服），属于机器人框架核心，
 * 故配置（{@code cross-server-channel} / {@code redis-*} / {@code server-name}）放在核心 config，
 * 由各平台主类在启动时创建并注入到桥（Velocity/BungeeCordBridge）或注册到服务总线（Bukkit 子服侧 SpigotBridge 取用）。
 * 附属插件（如 xt-auth）<b>不再</b>自建 Redis。
 */
public final class CrossServerChannelFactory {

    private CrossServerChannelFactory() {
    }

    /**
     * 创建结果：信道 + 关闭钩子（关闭时一并释放底层连接）。
     *
     * <p>刻意只暴露 {@link CrossServerChannel} 与一个 {@link Runnable} 关闭钩子，不在签名里出现
     * Redis/jedis 类型，使各平台主类引用 Holder 时无需把 jedis 放到自己的编译类路径。
     */
    public static final class Holder {
        public final CrossServerChannel channel;
        private final Runnable closer;

        Holder(CrossServerChannel channel, Runnable closer) {
            this.channel = channel;
            this.closer = closer;
        }

        public void close() {
            if (closer != null) {
                try { closer.run(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 按核心配置创建跨服信道。
     *
     * @param config   核心配置（读 cross-server-channel / redis-host / redis-port / redis-password）
     * @param isSpigot true=Bukkit 子服(手脚)，false=代理大脑(Velocity/BungeeCord)；用于 Redis 消息来源类型过滤
     * @param logger   日志
     * @return 信道持有者；未配置 Redis（或配置为 plugin-message）则返回 null（表示走原生 PluginMessage）。
     */
    public static Holder create(BotConfig config, boolean isSpigot, BotLogger logger) {
        String mode = config.getString("cross-server-channel", "plugin-message");
        String redisHost = config.getString("redis-host", "");
        boolean wantRedis = !redisHost.isEmpty() && mode.equals("redis");
        if (!wantRedis) {
            return null;
        }
        try {
            int port = config.getInt("redis-port", 6379);
            RedisManager manager = new RedisManager(
                    redisHost, port, config.getString("redis-password", ""), logger);
            // server-name：优先读配置值（如 "shelter"），握手后由 I_AM_BOSS 覆盖
            String serverName = config.getString("server-name", "server");
            RedisChannel channel = new RedisChannel(manager, serverName, isSpigot, logger);
            logger.info("[跨服] Redis 信道已启用 (" + redisHost + ":" + port + ")");
            return new Holder(channel, () -> {
                try { channel.close(); } catch (Exception ignored) { }
                try { manager.close(); } catch (Exception ignored) { }
            });
        } catch (Exception e) {
            logger.warn("[跨服] Redis 初始化失败，回退到 PluginMessage: " + e.getMessage());
            return null;
        }
    }
}
