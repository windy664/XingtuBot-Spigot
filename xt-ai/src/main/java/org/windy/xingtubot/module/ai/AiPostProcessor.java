package org.windy.xingtubot.module.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 输出后处理管线——<b>所有回复都必须经过这里</b>，不允许旁路。
 *
 * <p>模型产出直接发进 QQ 群是最露怯的地方：markdown 符号原样显示、开口就是
 * 「希望这对你有帮助」、被 @ 时把 {@code NO_REPLY} 这种内部标记甩给用户、
 * 一激动写 500 字小作文。这里按固定顺序处理干净。
 *
 * <p>处理顺序（每一步都可单测）：
 * <ol>
 *   <li>严格 NO_REPLY 判定 → 静默</li>
 *   <li>越狱特征检测 → 静默（模型自己说出「你现在是…」说明人设被带跑了）</li>
 *   <li>剥特殊标记（{@code [CQ:...]} / {@code <face ...>}）</li>
 *   <li>剥 markdown（{@code msg_type=TEXT} 不会渲染，只会原样显示符号）</li>
 *   <li>剥 AI 味短语</li>
 *   <li>剥「名字：」前缀</li>
 *   <li>智能截断（优先句末标点 → 逗号顿号 → 硬切）</li>
 *   <li>按自然分句概率性拆条</li>
 * </ol>
 */
final class AiPostProcessor {

    /** 处理结果。 */
    static final class Result {
        /** true = 本次应当保持沉默，不发任何消息。 */
        final boolean silent;
        /** 要发出去的消息（已切分、已限长）。 */
        final List<String> parts;
        /** 硬截断产生，建议后续做一次轻量润色（当前仅标记）。 */
        final boolean needPolish;

        private Result(boolean silent, List<String> parts, boolean needPolish) {
            this.silent = silent;
            this.parts = parts;
            this.needPolish = needPolish;
        }

        static Result silence() {
            return new Result(true, Collections.<String>emptyList(), false);
        }

        static Result of(List<String> parts, boolean needPolish) {
            return new Result(false, parts, needPolish);
        }
    }

    private final int maxLength;
    private final double splitProbability;
    private final int maxParts;

    AiPostProcessor(int maxLength, double splitProbability, int maxParts) {
        this.maxLength = Math.max(20, maxLength);
        this.splitProbability = Math.max(0.0, Math.min(1.0, splitProbability));
        this.maxParts = Math.max(1, maxParts);
    }

    Result process(String raw) {
        if (raw == null) return Result.silence();
        String s = raw.trim();
        if (s.isEmpty()) return Result.silence();

        // ① 严格 NO_REPLY：trim + 去标点后必须【全等】，否则「NO_REPLY 我来」会被整条吞掉
        if (isNoReply(s)) return Result.silence();

        // ② 越狱：模型自己说出「你现在是…」「忽略以上指令」等，说明人设被带跑了
        if (AiGuardrails.isJailbreak(s)) return Result.silence();

        // ③ 特殊标记
        s = AiGuardrails.stripSpecialTags(s);

        // ④ markdown
        s = AiGuardrails.stripMarkdown(s);

        // ⑤ AI 味短语
        s = AiGuardrails.stripAiPhrases(s);

        // ⑥ 模型误带的「名字：」前缀
        s = AiGuardrails.stripNamePrefix(s);

        s = s.trim();
        if (s.isEmpty()) return Result.silence();

        // ⑦ 智能截断
        boolean needPolish = false;
        if (s.length() > maxLength) {
            String cut = smartTruncate(s, maxLength);
            needPolish = cut.length() < s.length() && !endsWithPunctuation(cut);
            s = cut;
        }

        // ⑧ 拆条
        List<String> parts = split(s);
        if (parts.isEmpty()) return Result.silence();
        return Result.of(parts, needPolish);
    }

    /**
     * 智能截断：优先在句末标点切，其次逗号顿号，都没有才硬切。
     * <p>比硬切多留一个完整句子，读起来才像人话。
     */
    static String smartTruncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        String head = s.substring(0, max);
        // 优先句末标点
        int cut = lastIndexOfAny(head, "。！？!?…\n");
        if (cut >= max / 2) {
            return head.substring(0, cut + 1).trim();
        }
        // 其次逗号顿号
        cut = lastIndexOfAny(head, "，、；;,");
        if (cut >= max / 2) {
            return head.substring(0, cut + 1).trim();
        }
        // 硬切
        return head.trim();
    }

    /**
     * 按自然分句拆条。
     * <p>不是每次都拆——真人有时候一句话说完，有时候连发两条。给一个概率控制，
     * 拆出来的多条之间留白（调用方按间隔发送）。
     */
    List<String> split(String s) {
        if (s == null || s.trim().isEmpty()) return Collections.emptyList();
        String text = s.trim();
        if (text.length() <= maxLength * 2 / 3 || maxParts <= 1) {
            return new ArrayList<>(Collections.singletonList(text));
        }
        if (ThreadLocalRandom.current().nextDouble() > splitProbability) {
            return new ArrayList<>(Collections.singletonList(text));
        }
        List<String> raw = naturalParts(text);
        if (raw.size() <= 1) {
            return new ArrayList<>(Collections.singletonList(text));
        }
        List<String> out = new ArrayList<>();
        for (String part : raw) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (out.size() >= maxParts) break;
            out.add(p);
        }
        if (out.isEmpty()) out.add(text);
        return out;
    }

    /** 按自然分句边界切：句末标点优先，其次逗号顿号。 */
    static List<String> naturalParts(String s) {
        List<String> parts = new ArrayList<>();
        if (s == null || s.isEmpty()) return parts;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '…' || c == '\n') {
                addPart(parts, s.substring(start, i + 1));
                start = i + 1;
            } else if ((c == '，' || c == '、' || c == '；' || c == ',' || c == ';')
                    && i - start >= 6) {
                addPart(parts, s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < s.length()) addPart(parts, s.substring(start));
        return parts;
    }

    private static void addPart(List<String> parts, String raw) {
        String p = raw.trim();
        if (!p.isEmpty()) parts.add(p);
    }

    private static int lastIndexOfAny(String s, String chars) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static boolean endsWithPunctuation(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return "。！？!?…，、；;,".indexOf(c) >= 0;
    }

    /**
     * 严格 NO_REPLY 判定：去掉空白与标点后必须**全等** {@code NO_REPLY}。
     * <p>旧版用 {@code replaceAll("[^A-Z]","")} 归一化后比对，会把
     * 「NO_REPLY 我来」也判成 NO_REPLY，整条回复被静默吞掉。
     */
    static boolean isNoReply(String s) {
        if (s == null) return true;
        String t = s.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}]", "");
        if (t.isEmpty()) return true;
        return t.equals("NOREPLY");
    }
}
