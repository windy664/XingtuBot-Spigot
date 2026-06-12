package org.windy.xingtubot.common.platform;

import java.util.UUID;

/**
 * 平台适配接口：屏蔽 Spigot / Velocity 在调度、日志、广播上的差异。
 */
public interface PlatformAdapter {
    void runAsync(Runnable r);

    void runSync(Runnable r);

    void log(String msg);

    void broadcast(String msg);

    void sendMessageToPlayer(UUID uuid, String msg);
}
