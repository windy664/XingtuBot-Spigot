package org.windy.xingtubot.module.whitelist.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class WhiteListAPI {
    private static final File JSON_FILE = new File("whitelist.json");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithoutExposeAnnotation()  // 如果使用注解方式
            .create();
    private static List<WhiteListEntry> cachedEntries = loadEntries();

    private static List<WhiteListEntry> loadEntries() {
        if (!JSON_FILE.exists()) return new ArrayList<>();
        try (Reader reader = new FileReader(JSON_FILE)) {
            Type listType = new TypeToken<List<WhiteListEntry>>() {}.getType();
            return GSON.fromJson(reader, listType);
        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private static void saveEntries() {
        try (Writer writer = new FileWriter(JSON_FILE)) {
            GSON.toJson(cachedEntries, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取所有白名单条目
     */
    public static List<WhiteListEntry> getAllEntries() {
        return cachedEntries;
    }

    /**
     * 根据玩家名获取完整白名单条目
     */
    public static Optional<WhiteListEntry> getEntryByPlayer(String playerName) {
        return getAllEntries().stream()
                .filter(entry -> entry.player.equalsIgnoreCase(playerName))
                .findFirst();
    }

    /**
     * 根据QQ号获取完整白名单条目
     */
    public static Optional<WhiteListEntry> getEntryByQQ(String qq) {
        return getAllEntries().stream()
                .filter(entry -> entry.qq.equals(qq))
                .findFirst();
    }

    /**
     * 根据 formId 获取所有关联的玩家名
     */
    public static List<String> getPlayersByFormId(String formId) {
        return getAllEntries().stream()
                .filter(entry -> entry.formId != null && entry.formId.equals(formId))
                .map(entry -> entry.player)
                .collect(Collectors.toList());
    }




    /**
     * 判断某个玩家是否在白名单中
     */
    public static boolean isWhitelisted(String playerName) {
        return getEntryByPlayer(playerName).isPresent();
    }

    /**
     * 添加一条白名单记录
     */
    public static boolean addEntry(String formId, String player, String code, String qq) {
        if (isWhitelisted(player)) return false;

        int nextIndex = cachedEntries.size() + 1;
        WhiteListEntry entry = new WhiteListEntry(nextIndex, formId, player, code, qq);
        cachedEntries.add(entry);
        saveEntries();
        return true;
    }

    /**
     * 根据玩家名移除条目
     */
    public static boolean removeEntryByPlayer(String playerName) {
        Optional<WhiteListEntry> opt = getEntryByPlayer(playerName);
        if (opt.isPresent()) {
            cachedEntries.remove(opt.get());
            saveEntries();
            return true;
        }
        return false;
    }
    // 在WhiteListAPI类中添加
    public static class WhiteListEntry {
        public int index;
        public String timestamp;
        public String formId;
        public String player;
        public String code;
        public String qq;

        // 保持原有构造函数和方法
        public WhiteListEntry(int index, String formId, String player, String code, String qq) {
            this.index = index;
            this.formId = formId;
            this.player = player;
            this.code = code;
            this.qq = qq;
        }
    }
}