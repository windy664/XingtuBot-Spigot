package org.windy.xingtubot.common.config;

import java.util.List;

/**
 * 平台无关的配置读取接口。
 * Spigot 用 FileConfiguration 实现，Velocity 用 YAML 实现。
 */
public interface BotConfig {
    String getString(String path, String def);

    boolean getBoolean(String path, boolean def);

    int getInt(String path, int def);

    List<String> getStringList(String path);
}
