package org.windy.xingtubot.common.binding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 自动登录信任期的 JSON 持久化（{@code storage-type: json} 时使用）。
 *
 * <p>内存 Map + 文件回写，模式同 {@link BindingStore}。适合中小服；大玩家量建议用
 * sqlite/mysql（{@link JdbcAutoLoginRepository}）。写入时顺带惰性清理过期项，控制文件规模。
 */
public class JsonAutoLoginStore implements AutoLoginRepository {

    private final File file;
    private final Consumer<String> logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object lock = new Object();
    private final Map<String, Entry> map = new HashMap<>();

    public JsonAutoLoginStore(File file, Consumer<String> logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    private void load() {
        synchronized (lock) {
            if (!file.exists()) return;
            try (Reader reader = new FileReader(file)) {
                Type type = new TypeToken<Map<String, Entry>>() {}.getType();
                Map<String, Entry> loaded = gson.fromJson(reader, type);
                map.clear();
                if (loaded != null) map.putAll(loaded);
            } catch (IOException e) {
                log("读取自动登录数据失败: " + e.getMessage());
            }
        }
    }

    private void save() {
        // 调用方已持有 lock
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(map, writer);
        } catch (IOException e) {
            log("保存自动登录数据失败: " + e.getMessage());
        }
    }

    @Override
    public void put(String player, String ip, long expiry) {
        synchronized (lock) {
            purgeExpired();
            map.put(player.toLowerCase(), new Entry(ip, expiry));
            save();
        }
    }

    @Override
    public Entry get(String player) {
        synchronized (lock) {
            return map.get(player.toLowerCase());
        }
    }

    @Override
    public void remove(String player) {
        synchronized (lock) {
            if (map.remove(player.toLowerCase()) != null) save();
        }
    }

    /** 惰性清理过期项（调用方已持有 lock），避免文件无限增长。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        map.values().removeIf(e -> e.expiry <= now);
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }
}
