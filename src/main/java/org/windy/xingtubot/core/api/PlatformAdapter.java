package org.windy.xingtubot.core.api;

import java.util.List;
import java.util.UUID;

public interface PlatformAdapter {
    void runAsync(Runnable r);
    void runSync(Runnable r);
    void log(String msg);
    void broadcast(String msg);
    void sendMessageToPlayer(UUID uuid, String msg);
}