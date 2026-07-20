package org.windy.xingtubot.common.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 敏感词过滤器（平台无关）。本地词库 + 云端词库合并，支持忽略标点的穿插匹配。
 */
public class SensitiveFilter {

    private final boolean enabled;
    private final Set<String> localWords;
    private final Set<String> cloudIgnored;
    private final List<String> cloudUrls;
    private final Set<Character> ignoredPunctuations;
    private final char replacement;
    private final BotLogger logger;

    private volatile Set<String> allWords = Collections.synchronizedSet(new HashSet<>());

    /**
     * 从配置创建（顶层键，兼容旧模块各自的 config）。
     */
    public static SensitiveFilter fromConfig(BotConfig config, BotLogger logger) {
        return fromConfig(config, "", logger);
    }

    /**
     * 从配置创建（带前缀，如 prefix="sensitive-filter" → 读 sensitive-filter.Enable 等）。
     */
    public static SensitiveFilter fromConfig(BotConfig config, String prefix, BotLogger logger) {
        String p = (prefix == null || prefix.isEmpty()) ? "" : prefix + ".";
        boolean enabled = config.getBoolean(p + "Enable", false);

        List<String> localWords = config.getStringList(p + "Local");

        boolean cloudEnabled = config.getBoolean(p + "Cloud-Thesaurus.Enabled", false);
        List<String> cloudIgnored = config.getStringList(p + "Cloud-Thesaurus.Ignored");
        List<String> cloudUrls = config.getStringList(p + "Cloud-Thesaurus.Urls");

        List<String> ignoredPunctuations = config.getStringList(p + "Ignored-Punctuations");

        String repStr = config.getString(p + "Replacement", "*");
        char replacement = repStr.isEmpty() ? '*' : repStr.charAt(0);

        return new SensitiveFilter(
                enabled && cloudEnabled,
                localWords,
                cloudIgnored,
                cloudUrls,
                ignoredPunctuations,
                replacement,
                logger
        );
    }

    public SensitiveFilter(boolean enabled,
                           List<String> localWords,
                           List<String> cloudIgnored,
                           List<String> cloudUrls,
                           List<String> ignoredPunctuations,
                           char replacement,
                           BotLogger logger) {
        this.enabled = enabled;
        this.logger = logger;

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

        reloadLocalWords();

        if (enabled) {
            reloadCloudWords();
        }
    }

    private void reloadLocalWords() {
        allWords.clear();
        allWords.addAll(localWords);
    }

    /** 异步下载云词库，合并到 allWords */
    public void reloadCloudWords() {
        if (!enabled || cloudUrls.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                Set<String> cloudWords = new HashSet<>();
                Gson gson = new Gson();

                for (String url : cloudUrls) {
                    URL u = new URL(url);
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(u.openStream(), StandardCharsets.UTF_8))) {
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

                if (logger != null) logger.info("[敏感词] 云端词库已重载, 大小: " + allWords.size());
            } catch (Exception e) {
                if (logger != null) logger.warn("[敏感词] 无法下载云端词库: " + e.getMessage());
            }
        });
    }

    /**
     * 过滤文本，将敏感词替换成 replacement；匹配时忽略大小写与配置的标点。
     */
    public String filter(String input) {
        if (!enabled || input == null || input.isEmpty()) return input;

        String lowerInput = input.toLowerCase();

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
        boolean[] mask = new boolean[input.length()];

        for (String word : allWords) {
            if (word.length() == 0) continue;
            int index = 0;
            while ((index = pureStr.indexOf(word, index)) >= 0) {
                for (int i = index; i < index + word.length(); i++) {
                    int origIdx = originalIndexes.get(i);
                    mask[origIdx] = true;
                }
                index++;
            }
        }

        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            result.append(mask[i] ? replacement : input.charAt(i));
        }
        return result.toString();
    }
}
