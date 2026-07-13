package org.windy.xingtubot.common.binding;
import org.windy.xingtubot.common.binding.*;

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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 绑定数据的线程安全持久化（JSON）。
 *
 * <p>取代旧 whitelist.json 直接读写 ArrayList 的做法：所有读写加锁，
 * 以 openid / player 为唯一键（去掉脆弱的自增 index）。
 */
public class BindingStore implements BindingRepository {

    private final File file;
    private final Consumer<String> logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object lock = new Object();
    private final List<BindingEntry> entries = new ArrayList<>();

    public BindingStore(File file, Consumer<String> logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    private void load() {
        synchronized (lock) {
            if (!file.exists()) return;
            try (Reader reader = new FileReader(file)) {
                Type type = new TypeToken<List<BindingEntry>>() {}.getType();
                List<BindingEntry> loaded = gson.fromJson(reader, type);
                entries.clear();
                if (loaded != null) entries.addAll(loaded);
            } catch (IOException e) {
                log("读取绑定数据失败: " + e.getMessage());
            }
        }
    }

    private void save() {
        // 调用方已持有 lock
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer writer = new FileWriter(file)) {
            gson.toJson(entries, writer);
        } catch (IOException e) {
            log("保存绑定数据失败: " + e.getMessage());
        }
    }

    /** 新增/覆盖绑定（同 player 或同 openid 的旧记录会被替换）。 */
    public void put(BindingEntry entry) {
        synchronized (lock) {
            entries.removeIf(e -> e.player.equalsIgnoreCase(entry.player)
                    || (e.openid != null && e.openid.equals(entry.openid)));
            entries.add(entry);
            save();
        }
    }

    /** 解绑某玩家，返回是否删除了记录。 */
    public boolean removeByPlayer(String player) {
        synchronized (lock) {
            boolean removed = entries.removeIf(e -> e.player.equalsIgnoreCase(player));
            if (removed) save();
            return removed;
        }
    }

    public BindingEntry findByOpenid(String openid) {
        synchronized (lock) {
            return entries.stream()
                    .filter(e -> e.openid != null && e.openid.equals(openid))
                    .findFirst().orElse(null);
        }
    }

    public BindingEntry findByPlayer(String player) {
        synchronized (lock) {
            return entries.stream()
                    .filter(e -> e.player.equalsIgnoreCase(player))
                    .findFirst().orElse(null);
        }
    }

    public boolean isPlayerBound(String player) {
        return findByPlayer(player) != null;
    }

    /** 兼容群服互联：按 openid 取玩家名列表。 */
    public List<String> getPlayersByOpenid(String openid) {
        synchronized (lock) {
            return entries.stream()
                    .filter(e -> e.openid != null && e.openid.equals(openid))
                    .map(e -> e.player)
                    .collect(Collectors.toList());
        }
    }

    /** 全部绑定的副本（用于上报等）。 */
    public List<BindingEntry> all() {
        synchronized (lock) {
            return new ArrayList<>(entries);
        }
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }
}
