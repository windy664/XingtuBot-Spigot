package org.windy.xingtubot.bukkit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.windy.xingtubot.common.config.BotConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Override
    public Map<String, String> getStringMap(String path) {
        ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        for (String key : section.getKeys(false)) {
            String val = section.getString(key);
            if (val != null) map.put(key.toLowerCase(), val);
        }
        return map;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getStringMapList(String path) {
        List<?> raw = cfg.getMapList(path);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map) {
                Map<String, Object> entry = new HashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) item).entrySet()) {
                    entry.put(String.valueOf(e.getKey()), e.getValue());
                }
                result.add(entry);
            }
        }
        return result;
    }
}
