package org.windy.xingtubot.module.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务器知识库的词汇级粗检索（穷 RAG：无向量、无外部依赖、确定性）。
 * 中文按单字+相邻双字、英文按词切成 gram 集合，消息与知识行重叠打分，取 top-K 行。
 * 省 token 的关键：每轮只带相关条目，与消息毫无字面重叠时注入为空（零开销）；
 * 问法完全偏离字面时可能漏选，精确触发词另有问答直答兜底。
 * 知识行格式为 xt-group ReplyKnowledgeProvider 的「trigger（匹配标注） → 内容」。
 */
final class KnowledgeRetriever {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]{2,}");
    private static final String ARROW = " → ";

    private KnowledgeRetriever() {
    }

    /** 按与消息的重叠得分取 top-K 行；无任何重叠返回空列表。 */
    static List<String> topK(List<String> lines, String message, int topK) {
        List<String> out = new ArrayList<>();
        if (lines == null || lines.isEmpty() || message == null || message.isEmpty()) return out;
        Set<String> msgGrams = grams(message.toLowerCase());
        if (msgGrams.isEmpty()) return out;

        List<int[]> scored = new ArrayList<>(); // [行号, 得分]
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isEmpty()) continue;
            int s = score(line, msgGrams);
            if (s > 0) scored.add(new int[]{i, s});
        }
        scored.sort((a, b) -> Integer.compare(b[1], a[1]));
        for (int i = 0; i < scored.size() && out.size() < topK; i++) {
            out.add(lines.get(scored.get(i)[0]));
        }
        return out;
    }

    /** trigger 段权重 ×3（问法通常贴近触发词），整行 1 倍（内容词抬高换问法的召回）。 */
    private static int score(String line, Set<String> msgGrams) {
        int arrow = line.indexOf(ARROW);
        String head = arrow > 0 ? line.substring(0, arrow) : line;
        return 3 * hits(head, msgGrams) + hits(line, msgGrams);
    }

    private static int hits(String text, Set<String> msgGrams) {
        int n = 0;
        for (String g : grams(text.toLowerCase())) {
            if (msgGrams.contains(g)) n++;
        }
        return n;
    }

    /** CJK 单字（前缀 c）+ 相邻双字（前缀 b，天然 2 倍权重）+ ASCII 词。 */
    static Set<String> grams(String lower) {
        Set<String> set = new HashSet<>();
        Matcher w = ASCII_WORD.matcher(lower);
        while (w.find()) {
            set.add(w.group());
        }
        char prev = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isHan(c)) {
                set.add("c" + c);
                if (isHan(prev)) {
                    set.add("b" + prev + c);
                }
            }
            prev = c;
        }
        return set;
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
