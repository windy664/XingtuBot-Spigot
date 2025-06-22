package org.windy.xingtubot.bukkit.module.aichat;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AIChatKnowledgeBase {
    private final Map<String, String> docs = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> index = new ConcurrentHashMap<>();
    private final JiebaSegmenter segmenter = new JiebaSegmenter();
    private final boolean debug;
    private final Path folder = Paths.get("plugins/XingtuBot/knowledge");

    public AIChatKnowledgeBase(FileConfiguration config) {
        this.debug = config.getBoolean("debug", false);
        loadKnowledgeBase();
    }

    public void reload() {
        docs.clear();
        index.clear();
        loadKnowledgeBase();
        log("知识库已热重载。");
    }

    public void loadKnowledgeBase() {
        if (!Files.exists(folder)) {
            log("知识库文件夹不存在！");
            return;
        }

        try {
            Files.walk(folder)
                    .filter(Files::isRegularFile)
                    .forEach(this::loadFile);
        } catch (IOException e) {
            log("加载知识库失败：" + e.getMessage());
        }
    }

    private void loadFile(Path path) {
        try {
            String fileName = path.getFileName().toString().toLowerCase();
            String content;

            if (fileName.endsWith(".pdf")) {
                content = extractTextFromPDF(path);
            } else if (fileName.endsWith(".md") || fileName.endsWith(".txt") || fileName.endsWith(".yml")) {
                content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            } else {
                return;
            }

            docs.put(fileName, content);
            indexFile(fileName, content);
            log("已索引文件：" + fileName);
        } catch (IOException e) {
            log("读取文件失败：" + path.getFileName());
        }
    }

    private String extractTextFromPDF(Path path) throws IOException {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
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
