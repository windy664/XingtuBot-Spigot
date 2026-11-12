package org.windy.xingtubot.module.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 护栏词表与特殊标记处理（单一真源）。
 *
 * <p>以前这些规则散落在 {@code AiPromptBuilder}、{@code AiGuards} 和 core 的敏感词过滤器里，
 * 新增路径时容易漏——同类项目踩过的坑是「新增一条 LLM 文本路径没带身份护栏，
 * 结果把普通群友当成特殊身份称呼」。这里集中一份，改一处就够。
 *
 * <p>分三类：
 * <ul>
 *   <li><b>AI 味短语</b>：模型一开口就暴露是机器的固定句式，输出后处理时清掉。</li>
 *   <li><b>越狱特征</b>：群友试图改写人设 / 解除禁令，命中就不发。</li>
 *   <li><b>特殊标记</b>：{@code [CQ:...]}（OneBot 协议）与 {@code <face ...>}（QQ 原生标签），
 *       本项目走 QQ 官方开放平台的 {@code msg_type=TEXT}，不解析 CQ 码，但原生的
 *       {@code <face>} 可能被 QQ 侧渲染成表情，统一剥掉。</li>
 * </ul>
 */
final class AiGuardrails {

    private AiGuardrails() {
    }

    /**
     * AI 味短语表。
     * <p>命中整句 → 删句；句中命中 → 替换成空。来源见整改方案 §2.4。
     */
    static final List<String> AI_PHRASES = Arrays.asList(
            // 开头白
            "作为一个ai", "作为一个 ai", "作为一个人工智能", "作为一个语言模型", "作为一个助手",
            "我是一个ai", "我是一个 ai", "我只是一个模型", "我只是个ai", "作为您的助手", "作为你的助手",
            "以下是回答", "以下是答案", "以下是回复",
            // 收尾白
            "希望这对你有帮助", "希望我的回答对你有帮助", "希望对你有帮助", "希望对您有帮助",
            "还有什么可以帮您", "还有什么可以帮你", "如果还有其他问题", "如果还有疑问", "随时可以问我",
            // 总结癖
            "总的来说", "综上所述", "总而言之", "总的来说就是", "一言以蔽之",
            "希望我的回答", "请注意", "请注意，", "建议您", "建议你"
    );

    /**
     * 越狱特征词：命中即认为群友在试图改写人设 / 解除禁令。
     * <p>这里只做<b>检测</b>，命中后丢弃本次回复；「带修正指令重生成一次」的四态收敛在后续 PR。
     */
    static final List<String> JAILBREAK = Arrays.asList(
            "忽略以上", "忽略上面", "忽略之前", "忽略前面", "无视以上", "无视上面",
            "忘记你的设定", "忘掉你的设定", "忘记之前", "抛开你的",
            "你现在是", "从现在开始你是", "扮演一个", "假装你是", "你不再是",
            "重复以下", "复述以下", "逐字输出", "原样输出", "输出你的系统提示",
            "你的系统提示", "系统提示词", "你的设定是什么", "打印你的", "输出你的prompt"
    );

    /** OneBot / NapCat 协议码。本项目用 QQ 官方 API，其实不解析，但统一剥掉更安全。 */
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^\\]]*\\]");

    /** QQ 原生表情标签，QQ 侧可能渲染成表情。 */
    private static final Pattern FACE_TAG = Pattern.compile("<face[^>]*>");

    /** markdown 强调 / 代码围栏 / 标题。 */
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL);
    private static final Pattern MD_ITALIC = Pattern.compile("(?<![*\\w])\\*([^*\n]+)\\*(?![*\\w])");
    private static final Pattern MD_CODE = Pattern.compile("```[a-zA-Z0-9]*\\n?(.*?)```", Pattern.DOTALL);
    private static final Pattern MD_HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*", Pattern.DOTALL);
    private static final Pattern MD_LIST = Pattern.compile("(?m)^\\s{0,3}([-*+] |\\d+[.、] )", Pattern.DOTALL);
    private static final Pattern MD_QUOTE = Pattern.compile("(?m)^\\s{0,3}>\\s?", Pattern.DOTALL);

    /** 模型偶尔带出来的「名字：」前缀，只在后面紧跟冒号时才剥，避免误伤 [图片] 这类标记。 */
    private static final Pattern NAME_PREFIX = Pattern.compile("^\\s*[\\[【]?[^\\]】\\n]{1,16}[\\]】]?\\s*[:：]\\s*");

    /**
     * 是否命中越狱特征。
     * <p>只在<b>模型输出</b>上判定：群友在群里说「你现在是」是正常聊天，
     * 不该被当成攻击；但模型<b>自己</b>说出来就说明人设被带跑了。
     */
    static boolean isJailbreak(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String kw : JAILBREAK) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** 剥掉 QQ 原生标签与 OneBot 协议码。 */
    static String stripSpecialTags(String text) {
        if (text == null) return "";
        String s = CQ_CODE.matcher(text).replaceAll("");
        s = FACE_TAG.matcher(s).replaceAll("");
        return s;
    }

    /** 剥掉 markdown 排版（QQ 的 msg_type=TEXT 不会渲染 markdown，只会原样显示符号）。 */
    static String stripMarkdown(String text) {
        if (text == null) return "";
        String s = text;
        s = MD_CODE.matcher(s).replaceAll("$1");
        s = MD_BOLD.matcher(s).replaceAll("$1");
        s = MD_ITALIC.matcher(s).replaceAll("$1");
        s = MD_HEADING.matcher(s).replaceAll("");
        s = MD_QUOTE.matcher(s).replaceAll("");
        s = MD_LIST.matcher(s).replaceAll("");
        // 残留的行内单星号 / 反引号
        s = s.replace("`", "");
        s = s.replace("**", "");
        return s;
    }

    /**
     * 剥掉 AI 味短语。
     * <p>整句命中 → 整句删；句中命中 → 只删短语本身，保留其余内容。
     * <p>匹配必须<b>忽略大小写</b>：词表里存的是小写（"作为一个ai"），
     * 模型却会输出 "作为一个AI"，只小写化后比对、拿原文替换是替换不掉的。
     */
    static String stripAiPhrases(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] sentences = text.split("(?<=[。！？!?\\n])");
        StringBuilder out = new StringBuilder();
        for (String raw : sentences) {
            String s = raw;
            boolean wholeSentence = false;
            for (String phrase : AI_PHRASES) {
                Matcher m = Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE).matcher(s);
                if (!m.find()) continue;
                String stripped = m.replaceAll("").replace("，", "").replace(",", "").trim();
                if (stripped.isEmpty() || stripped.length() <= 2) {
                    // 整句就是这句套话 → 整句丢
                    wholeSentence = true;
                    break;
                }
                s = stripped;
            }
            if (!wholeSentence) out.append(s);
        }
        return out.toString().trim();
    }

    /** 剥掉模型误带的「名字：」前缀。 */
    static String stripNamePrefix(String text) {
        if (text == null) return "";
        return NAME_PREFIX.matcher(text).replaceFirst("");
    }

    /** 把可能被误当成指令的内容做标记（给提示词用）。 */
    static List<String> forbiddenLines() {
        return new ArrayList<>(AI_PHRASES);
    }
}
