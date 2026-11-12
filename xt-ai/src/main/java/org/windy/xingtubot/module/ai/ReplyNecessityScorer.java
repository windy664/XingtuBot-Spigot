package org.windy.xingtubot.module.ai;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 「要不要插话」的规则打分器。
 *
 * <h3>为什么在模型之前先算分</h3>
 * 旧版每条群消息都要先调一次便宜模型问「要不要插话」，一次都没插话也照烧钱。
 * 先用纯规则算个分，<b>低于阈值直接不插话、连 LLM 都不调</b>——省钱、降延迟，
 * 也降低了被人刷屏刷出大量请求的风险。
 *
 * <p>打分思路参考同类成熟实现（Pallas-Bot 的 {@code reply_necessity.py}）：
 * 该说的信号加分（被 @、被点名、有人求助、在追问），该安静的信号减分
 * （刚说过、刷屏、垃圾推广、两人私聊、短反应）。
 *
 * <p>打分器只做<b>粗筛</b>，最终要不要说仍由模型判定——规则擅长排除，
 * 不擅长判断"这个梗接不接得住"。
 */
final class ReplyNecessityScorer {

    /** 请求 / 求助类词汇。 */
    private static final Set<String> REQUEST_WORDS = new HashSet<>(Arrays.asList(
            "能不能", "可不可以", "可以吗", "怎么", "如何", "怎么办", "求助", "请问",
            "帮忙", "帮我", "问一下", "求个", "谁知道", "求教", "咋弄", "咋办", "求助一下"));

    /** 推广 / 垃圾信息特征。 */
    private static final String[] PROMO_WORDS = {
            "加群", "私聊我", "代练", "优惠券", "优惠码", "折扣", "招募", "推广",
            "扫码", "关注公众号", "点击链接", "http://", "https://"
    };

    /** 刷屏特征：连续重复同一字符。 */
    private static final int REPEAT_THRESHOLD = 6;

    private ReplyNecessityScorer() {
    }

    /** 打分上下文。 */
    static final class Context {
        /** 机器人在群里的名字。 */
        final String botName;
        /** 机器人最近（5 分钟内）是不是刚说过话。 */
        final boolean botSpokeRecently;
        /** 群里上一条消息（可能是别人的），用于判「两人对话」和「复读」。 */
        final String previousMessage;
        /** 上一条消息是谁说的。 */
        final String previousAuthor;

        Context(String botName, boolean botSpokeRecently,
                String previousMessage, String previousAuthor) {
            this.botName = botName;
            this.botSpokeRecently = botSpokeRecently;
            this.previousMessage = previousMessage;
            this.previousAuthor = previousAuthor;
        }

        static Context empty(String botName) {
            return new Context(botName, false, null, null);
        }
    }

    /**
     * 给一条群消息打分。
     *
     * @return 分值；越高越值得插话
     */
    static int score(String message, Context ctx) {
        if (message == null) return -100;
        String msg = message.trim();
        if (msg.isEmpty()) return -100;

        int s = 0;
        String botName = ctx == null ? null : ctx.botName;

        // ── 该说的信号 ──
        if (botName != null && !botName.isEmpty() && msg.contains(botName)) {
            s += msg.contains("@" + botName) ? 55 : 35;
        }
        for (String w : REQUEST_WORDS) {
            if (msg.contains(w)) {
                s += 25;
                break;
            }
        }
        if (msg.contains("？") || msg.contains("?")) {
            s += 20;
        }
        // 短问句多半是在追问
        if (msg.length() <= 15 && (msg.contains("？") || msg.contains("?"))) {
            s += 25;
        }

        // ── 该安静的信号 ──
        if (ctx != null && ctx.botSpokeRecently) {
            s -= 45;   // 刚说过就别急着连发
        }
        if (msg.length() <= 4) {
            s -= 25;   // 「哈哈」「+1」这种短反应
        }
        if (isRepeated(msg)) {
            s -= 40;   // 刷屏
        }
        if (isPromo(msg)) {
            s -= 50;   // 推广垃圾
        }
        if (ctx != null && ctx.previousMessage != null) {
            // 复读：跟上一条几乎一样 → 群在刷屏
            if (normalize(ctx.previousMessage).equals(normalize(msg))) {
                s -= 40;
            }
            // ⚠️ 这里<b>不</b>给"群友在互相说话"扣分。
            // 群里每条消息后面几乎都跟着别人说话，真扣了就会变成永远插不上嘴。
            // "这是两人私聊、我插不上脚"交给判定模型判断——它有完整上下文，
            // 而规则分不清"两人私聊"和"群友随口说一句"。
        }

        return s;
    }

    /** 连续重复同一字符达到阈值 → 刷屏。 */
    static boolean isRepeated(String msg) {
        if (msg == null || msg.length() < REPEAT_THRESHOLD) return false;
        int run = 1;
        for (int i = 1; i < msg.length(); i++) {
            if (msg.charAt(i) == msg.charAt(i - 1)) {
                run++;
                if (run >= REPEAT_THRESHOLD) return true;
            } else {
                run = 1;
            }
        }
        return false;
    }

    /** 推广 / 垃圾信息。 */
    static boolean isPromo(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        for (String w : PROMO_WORDS) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    /** 归一化后比较（去掉空白与标点），用于判复读。 */
    static String normalize(String msg) {
        if (msg == null) return "";
        return msg.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
    }
}
