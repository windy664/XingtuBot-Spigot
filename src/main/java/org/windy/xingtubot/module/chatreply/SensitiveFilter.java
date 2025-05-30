package org.windy.xingtubot.module.chatreply;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SensitiveFilter {

    private final boolean enabled;
    private final Set<String> localWords;
    private final Set<String> cloudIgnored;
    private final List<String> cloudUrls;
    private final Set<Character> ignoredPunctuations;
    private final char replacement;

    // 敏感词合并库，全部转小写，匹配时不区分大小写
    private volatile Set<String> allWords = Collections.synchronizedSet(new HashSet<>());
    public static SensitiveFilter fromConfig(FileConfiguration config) {
        boolean enabled = config.getBoolean("Enable", false);

        // 读取本地敏感词列表
        List<String> localWords = config.getStringList("Local");

        // 云词库配置
        boolean cloudEnabled = config.getBoolean("Cloud-Thesaurus.Enabled", false);
        List<String> cloudIgnored = config.getStringList("Cloud-Thesaurus.Ignored");
        List<String> cloudUrls = config.getStringList("Cloud-Thesaurus.Urls");

        // 忽略符号列表
        List<String> ignoredPunctuations = config.getStringList("Ignored-Punctuations");

        // 替换字符，配置是字符串，取第一个字符，没有则默认 '*'
        String repStr = config.getString("Replacement", "*");
        char replacement = repStr.isEmpty() ? '*' : repStr.charAt(0);

        return new SensitiveFilter(
                enabled && cloudEnabled,
                localWords,
                cloudIgnored,
                cloudUrls,
                ignoredPunctuations,
                replacement
        );
    }
    public SensitiveFilter(boolean enabled,
                           List<String> localWords,
                           List<String> cloudIgnored,
                           List<String> cloudUrls,
                           List<String> ignoredPunctuations,
                           char replacement) {
        this.enabled = enabled;
        this.localWords = new HashSet<>();
        for (String w : localWords) this.localWords.add(w.toLowerCase());

        this.cloudIgnored = new HashSet<>();
        for (String w : cloudIgnored) this.cloudIgnored.add(w.toLowerCase());

        this.cloudUrls = cloudUrls;
        this.ignoredPunctuations = new HashSet<>();
        for (String p : ignoredPunctuations) {
            if (p.length() == 1) {
                this.ignoredPunctuations.add(p.charAt(0));
            }
        }
        this.replacement = replacement;

        // 初始合并本地词库
        reloadLocalWords();

        // 异步加载远程词库
        if (enabled) {
            reloadCloudWords();
        }
    }

    private void reloadLocalWords() {
        allWords.clear();
        allWords.addAll(localWords);
    }

    /**
     * 异步下载云词库，合并到allWords
     */
    public void reloadCloudWords() {
        if (!enabled || cloudUrls.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                Set<String> cloudWords = new HashSet<>();
                Gson gson = new Gson();

                for (String url : cloudUrls) {
                    URL u = new URL(url);
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(u.openStream(), StandardCharsets.UTF_8))) {
                        JsonObject json = JsonParser.parseReader(br).getAsJsonObject();
                        Type listType = new TypeToken<List<String>>() {}.getType();
                        List<String> words = gson.fromJson(json.getAsJsonArray("words"), listType);
                        for (String w : words) {
                            String low = w.toLowerCase();
                            if (!cloudIgnored.contains(low)) {
                                cloudWords.add(low);
                            }
                        }
                    }
                }

                synchronized (allWords) {
                    allWords.addAll(cloudWords);
                }

                Bukkit.getLogger().info("[XintuChat] 云端敏感词已重载, 大小: " + allWords.size());
            } catch (Exception e) {
                Bukkit.getLogger().warning("[XintuChat] 无法下载云端敏感词: " + e.getMessage());
            }
        });
    }

    /**
     * 过滤文本，将敏感词替换成replacement
     * 忽略ignoredPunctuations中符号，匹配时忽略大小写
     */
    public String filter(String input) {
        if (!enabled || input == null || input.isEmpty()) return input;

        String lowerInput = input.toLowerCase();

        // 先构造一个去除符号的纯字母数字序列和对应索引映射
        StringBuilder pureBuilder = new StringBuilder();
        List<Integer> originalIndexes = new ArrayList<>();

        for (int i = 0; i < lowerInput.length(); i++) {
            char c = lowerInput.charAt(i);
            if (!ignoredPunctuations.contains(c)) {
                pureBuilder.append(c);
                originalIndexes.add(i);
            }
        }

        String pureStr = pureBuilder.toString();

        // 找到所有匹配敏感词的位置
        // 这里用最简单的暴力查找，性能不高但逻辑简单
        boolean[] mask = new boolean[input.length()]; // 标记敏感词所在字符是否需替换

        for (String word : allWords) {
            if (word.length() == 0) continue;
            int index = 0;
            while ((index = pureStr.indexOf(word, index)) >= 0) {
                // 根据纯字符串映射回原始索引，标记mask
                for (int i = index; i < index + word.length(); i++) {
                    int origIdx = originalIndexes.get(i);
                    mask[origIdx] = true;
                }
                index++;
            }
        }

        // 构造输出字符串
        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            if (mask[i]) {
                result.append(replacement);
            } else {
                result.append(input.charAt(i));
            }
        }
        return result.toString();
    }
}