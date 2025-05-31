package org.windy.xingtubot.module.aichat;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AIChatKnowledgeBase {
    private final Map<String, String> docs = new HashMap<>();
    private final Map<String, Set<String>> index = new ConcurrentHashMap<>();
    private final JiebaSegmenter segmenter = new JiebaSegmenter();
    private final boolean debug;

    public AIChatKnowledgeBase(FileConfiguration config) {
        this.debug = config.getBoolean("debug", false);
        loadKnowledgeBase();
    }

    public void loadKnowledgeBase() {
        Path folder = Paths.get("plugins/XingtuBot/knowledge");
        if (!Files.exists(folder)) {
            log("知识库文件夹不存在！");
            return;
        }

        try {
            Files.walk(folder)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            String fileName = path.getFileName().toString().toLowerCase();
                            if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")) return;

                            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                            docs.put(fileName, content);
                            indexFile(fileName, content);
                            log("已索引文件：" + fileName);
                        } catch (IOException e) {
                            log("读取文件失败：" + path.getFileName());
                        }
                    });
        } catch (IOException e) {
            log("加载知识库失败：" + e.getMessage());
        }
    }

    private void indexFile(String fileName, String content) {
        Set<String> tokens = tokenize(content);
        for (String word : tokens) {
            index.computeIfAbsent(word, k -> ConcurrentHashMap.newKeySet()).add(fileName);
        }
    }

    public String matchRelatedContent(String msg) {
        Set<String> words = tokenize(msg);
        Map<String, Integer> scoreMap = new HashMap<>();

        for (String word : words) {
            Set<String> files = index.get(word);
            if (files == null) continue;
            for (String file : files) {
                scoreMap.put(file, scoreMap.getOrDefault(file, 0) + 1);
            }
        }

        return scoreMap.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(2)
                .map(e -> "【" + e.getKey() + "】\n" + docs.get(e.getKey()))
                .collect(Collectors.joining("\n\n"));
    }

    private Set<String> tokenize(String text) {
        return new HashSet<>(segmenter.sentenceProcess(text));
    }

    private void log(String msg) {
        if (debug) {
            System.out.println("[知识库调试] " + msg);
        }
    }
}
