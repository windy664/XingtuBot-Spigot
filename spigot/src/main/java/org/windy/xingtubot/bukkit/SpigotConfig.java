package org.windy.xingtubot.bukkit;

import org.bukkit.configuration.file.FileConfiguration;
import org.windy.xingtubot.common.config.BotConfig;

import java.util.List;

/**
 * 用 Bukkit FileConfiguration 实现 common 的 BotConfig。
 */
public class SpigotConfig implements BotConfig {
    private final FileConfiguration cfg;

    public SpigotConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    @Override
    public String getString(String path, String def) {
        return cfg.getString(path, def);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return cfg.getBoolean(path, def);
    }

    @Override
    public int getInt(String path, int def) {
        return cfg.getInt(path, def);
    }

    @Override
    public List<String> getStringList(String path) {
        return cfg.getStringList(path);
    }
}
