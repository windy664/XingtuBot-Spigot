package org.windy.xingtubot.module.ai;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 文本相似度（字符二元组余弦），用来防复读机。
 *
 * <p>真人不会连着说三句结构一样的话。AI 会——尤其在提示词只说「简短自然一点」
 * 而没有示例的时候。判重放在输出层：新回复和最近几条机器人回复太像就丢掉。
 *
 * <p>用<b>字符二元组</b>而不是词：对中文更稳（不需要分词），
 * 而且「挺好的」和「挺好的呀」这种差异也能区分出来。
 */
final class TextSimilarity {

    private TextSimilarity() {
    }

    /**
     * 相似度：0（完全不同）～ 1（几乎一样）。
     */
    static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        String x = a.trim();
        String y = b.trim();
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return 1.0;

        Map<String, Integer> ga = bigrams(x);
        Map<String, Integer> gb = bigrams(y);
        if (ga.isEmpty() || gb.isEmpty()) return 0;

        double dot = 0;
        for (Map.Entry<String, Integer> e : ga.entrySet()) {
            Integer other = gb.get(e.getKey());
            if (other != null) dot += e.getValue() * other;
        }
        if (dot == 0) return 0;
        return dot / (norm(ga) * norm(gb));
    }

    /** 是否与给定列表里的任意一条太像。 */
    static boolean isTooSimilar(String text, Iterable<String> previous, double threshold) {
        if (previous == null) return false;
        for (String p : previous) {
            if (similarity(text, p) >= threshold) return true;
        }
        return false;
    }

    private static Map<String, Integer> bigrams(String s) {
        Map<String, Integer> m = new HashMap<>();
        String t = s.replaceAll("\\s+", "");
        if (t.length() < 2) {
            if (!t.isEmpty()) m.put(t, 1);
            return m;
        }
        for (int i = 0; i < t.length() - 1; i++) {
            String g = t.substring(i, i + 2);
            Integer c = m.get(g);
            m.put(g, c == null ? 1 : c + 1);
        }
        return m;
    }

    private static double norm(Map<String, Integer> m) {
        double s = 0;
        for (Integer v : m.values()) {
            s += (double) v * v;
        }
        return Math.sqrt(s);
    }
}
