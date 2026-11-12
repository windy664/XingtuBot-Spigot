package org.windy.xingtubot.module.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.ai.AiService;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.runtime.BotRuntimeState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 自主参与（chime-in）控制：先用规则打分器粗筛，再让轻量模型判断要不要接话，
 * 最后用「攒消息数 + 随机阈值 + 冷却 + 注意力」的节奏限制控制插话频率。
 *
 * <h3>线程模型</h3>
 * {@link #judge(String, String)} 会发网络请求，<b>只能在 AI 专属线程池里调用</b>。
 * {@link #recordCandidate(String)}、{@link #canReply(String)}、{@link #scoreOf(String, String)}
 * 都是纯计算，放在 matches() 阶段（QQ 网关线程）执行没有问题。
 *
 * <h3>三层漏斗</h3>
 * <ol>
 *   <li><b>规则打分器</b>：分低于阈值 → 直接不插话，<b>连 LLM 都不调</b>（省钱 + 降延迟）</li>
 *   <li><b>便宜模型判定</b>：JSON 契约，失败一律保守 PASS（fail-closed）</li>
 *   <li><b>回复概率</b>：模型说该说之后，再按频率与注意力掷一次骰子</li>
 * </ol>
 *
 * <h3>为什么没有"决策暂存"</h3>
 * 旧版在 matches() 阶段判定并把结果塞进一个 Map，handle() 再取出来用——跨方法的隐式
 * 状态传递，key 用 {@code msg.hashCode()}，同人群发相同消息会撞车。
 * 现在改成：判定与生成在 AI 线程池的<b>同一个任务</b>里顺序完成，
 * 「要不要攻击」只取决于 senderId（纯函数），不再需要任何跨方法传状态。
 */
final class ChimeInController {

    static final String NO_REPLY = "NO_REPLY";

    private final AiService chimeInJudgeService;
    private final BotConfig config;
    private final BotLogger logger;
    private final GroupContextMemory groupContext;

    private final ConcurrentHashMap<String, ChimeInCadence> cadenceByGroup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attention> attentionByGroup = new ConcurrentHashMap<>();

    ChimeInController(AiService chimeInJudgeService, BotConfig config, BotLogger logger,
                      GroupContextMemory groupContext) {
        this.chimeInJudgeService = chimeInJudgeService;
        this.config = config;
        this.logger = logger;
        this.groupContext = groupContext;
    }

    /** 记一条候选消息（攒节奏用）。纯内存操作，可在网关线程调用。 */
    void recordCandidate(String guildId) {
        cadence(guildId).recordMessage();
    }

    /** 当前节奏下是否允许普通插话。纯计算，可在网关线程调用。 */
    boolean canReply(String guildId) {
        int minMessages = Math.max(1, config.getInt("chime-in-min-messages", 3));
        int maxMessages = Math.max(minMessages, config.getInt("chime-in-max-messages", 6));
        long cooldownMs = Math.max(0, config.getInt("chime-in-cooldown-seconds", 60)) * 1000L;
        // 注意力越高，冷却越短（最多缩短一半）
        double att = attention(guildId).current();
        double factor = Math.max(0.5, 1.0 - att / 200.0);
        return cadence(guildId).canReply(minMessages, maxMessages, (long) (cooldownMs * factor));
    }

    /** 普通插话已回复，重置节奏并摇下一个随机阈值。 */
    void markReplied(String guildId) {
        int minMessages = Math.max(1, config.getInt("chime-in-min-messages", 3));
        int maxMessages = Math.max(minMessages, config.getInt("chime-in-max-messages", 6));
        cadence(guildId).markReply(minMessages, maxMessages);
    }

    /**
     * 规则打分器得分（纯计算，不发网络请求）。
     *
     * @return 分值；低于 {@code reply-necessity-threshold} 就不该插话
     */
    int scoreOf(String message, String guildId) {
        GroupContextMemory.CtxMessage prev = groupContext.previousMessage(guildId);
        ReplyNecessityScorer.Context ctx = new ReplyNecessityScorer.Context(
                BotRuntimeState.getBotName(),
                spokeRecently(guildId),
                prev == null ? null : prev.content,
                prev == null ? null : prev.username);
        return ReplyNecessityScorer.score(message, ctx);
    }

    /** 机器人最近 5 分钟内是不是刚说过话。 */
    boolean spokeRecently(String guildId) {
        long last = cadence(guildId).lastReplyAt();
        return last > 0 && System.currentTimeMillis() - last < 5 * 60_000L;
    }

    /**
     * 最终回复概率：基础频率 × 注意力加成。
     * 模型说「该说」之后还要掷这一次骰子——真人不会每次都接话。
     */
    boolean rollReplyProbability(String guildId) {
        double base = parseDouble(config.getString("group-chat-frequency", "0.2"), 0.2);
        if (base <= 0) return false;
        if (base >= 1) return true;
        double att = attention(guildId).current();
        double p = Math.min(0.9, base * (1.0 + att / 100.0));
        return ThreadLocalRandom.current().nextDouble() < p;
    }

    /** 提升某个群的注意力（被 @ / 被点名 / 命中兴趣时）。 */
    void bumpAttention(String guildId, double delta) {
        attention(guildId).add(delta);
    }

    /**
     * 让轻量模型判断要不要插话。
     *
     * <p><b>会发网络请求，只能在 AI 线程池里调用。</b>
     */
    JudgeDecision judge(String msg, String guildId) {
        if (chimeInJudgeService == null) return JudgeDecision.pass();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(AiPromptBuilder.message("system", judgePrompt()));

        List<GroupContextMemory.CtxMessage> ctx = groupContext.getSnapshot(guildId);
        if (!ctx.isEmpty()) {
            StringBuilder recent = new StringBuilder();
            recent.append("最近群聊（从旧到新，最后一条是最新消息）：\n");
            int start = Math.max(0, ctx.size() - 15);
            for (int i = start; i < ctx.size(); i++) {
                recent.append(ctx.get(i).toString()).append("\n");
            }
            messages.add(AiPromptBuilder.message("user", recent.toString()));
        }

        messages.add(AiPromptBuilder.message("user",
                "最新消息=" + msg + "\n你要不要插话？只输出上面规定格式的 JSON，不要任何其他文字。"));

        try {
            String decision = chimeInJudgeService.chat(messages);
            return parse(decision);
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] chime-in judge failed: " + e.getMessage());
            return JudgeDecision.pass();
        }
    }

    /** 判定用的系统词：JSON 契约 + 判定标准。 */
    private String judgePrompt() {
        return "你是群聊情商判断器。判断要不要插话，**只输出一个 JSON 对象**，不要任何其他文字、不要代码块围栏。\n\n"
                + "格式：{\"action\":\"PASS\",\"social_action\":\"ACK\",\"delivery_style\":\"简短\"}\n\n"
                + "action（必填）：\n"
                + "- \"REPLY\" 值得插话\n"
                + "- \"PASS\" 不插话\n\n"
                + "social_action（action=REPLY 时填，挑一个）：\n"
                + "- \"ACK\" 附和一下\n"
                + "- \"AFFECTION\" 表达关心\n"
                + "- \"JOKE\" 抖机灵\n"
                + "- \"STANCE\" 表态\n"
                + "- \"ANSWER\" 回答问题\n"
                + "- \"ASK_ONE\" 追问一句\n\n"
                + "delivery_style（action=REPLY 时填，挑一个）：简短 / 调侃 / 共情 / 反问 / 玩梗\n\n"
                + "值得插话（REPLY）的情况：\n"
                + "- 话题跟你有关（你之前说过的话、你了解的领域、你帮得上忙）\n"
                + "- 有人在求助/提问，你能给出有用的回复\n"
                + "- 气氛轻松，你说句话能接住话题、加点趣味或共鸣\n"
                + "- 有人说了个有趣的事，你有自然的反应想说\n\n"
                + "不该插话（PASS）的情况：\n"
                + "- 话题是两个人之间的私事、你插不上嘴\n"
                + "- 聊得很热烈了，你挤进去只会打断节奏\n"
                + "- 你没什么想说的，硬接话会很尬\n"
                + "- 话题敏感（政治/色情/争议），不该参与\n"
                + "- 最近几条消息节奏很快（刷屏/斗图），不适合插话\n\n"
                + "你在群里的名字是「" + BotRuntimeState.getBotName() + "」；"
                + "群聊记录里以它开头的消息是你自己发的，不算别人说话。";
    }

    /**
     * 解析判定结果。
     * <p>优先按 JSON 契约解析；解析不了再退回旧的纯文本协议（CHIME_IN / NO_REPLY）；
     * 都认不出来 → <b>保守按不插话处理</b>（fail-closed，宁可少说不可乱说）。
     */
    static JudgeDecision parse(String raw) {
        if (raw == null) return JudgeDecision.pass();
        String s = raw.trim();
        if (s.isEmpty()) return JudgeDecision.pass();

        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l >= 0 && r > l) {
            try {
                JsonObject o = JsonParser.parseString(s.substring(l, r + 1)).getAsJsonObject();
                String action = o.has("action") && !o.get("action").isJsonNull()
                        ? o.get("action").getAsString().trim() : "PASS";
                boolean reply = "REPLY".equalsIgnoreCase(action);
                String social = o.has("social_action") && !o.get("social_action").isJsonNull()
                        ? o.get("social_action").getAsString().trim() : "";
                String delivery = o.has("delivery_style") && !o.get("delivery_style").isJsonNull()
                        ? o.get("delivery_style").getAsString().trim() : "";
                return new JudgeDecision(reply, social, delivery);
            } catch (Exception ignored) {
                // 落到下面的纯文本兼容
            }
        }

        String norm = s.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z_]", "");
        if (norm.contains("NO_REPLY") || norm.contains("NOREPLY")) return JudgeDecision.pass();
        if (norm.contains("CHIME_IN") || norm.contains("CHIMEIN")) {
            return new JudgeDecision(true, "ACK", "简短");
        }
        return JudgeDecision.pass();
    }

    private static double parseDouble(String raw, double def) {
        if (raw == null || raw.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private ChimeInCadence cadence(String guildId) {
        String key = guildId == null ? "" : guildId;
        return cadenceByGroup.computeIfAbsent(key, k -> new ChimeInCadence());
    }

    private Attention attention(String guildId) {
        String key = guildId == null ? "" : guildId;
        return attentionByGroup.computeIfAbsent(key, k -> new Attention());
    }

    /** 判定结论。 */
    static final class JudgeDecision {
        final boolean reply;
        /** ACK / AFFECTION / JOKE / STANCE / ANSWER / ASK_ONE */
        final String socialAction;
        /** 简短 / 调侃 / 共情 / 反问 / 玩梗 */
        final String deliveryStyle;

        JudgeDecision(boolean reply, String socialAction, String deliveryStyle) {
            this.reply = reply;
            this.socialAction = socialAction == null ? "" : socialAction;
            this.deliveryStyle = deliveryStyle == null ? "" : deliveryStyle;
        }

        static JudgeDecision pass() {
            return new JudgeDecision(false, "", "");
        }

        /** 附和类回应（「确实」「哈哈」）——真人这种时候很短。 */
        boolean isShortAck() {
            return "ACK".equalsIgnoreCase(socialAction);
        }
    }

    /** 每群一份的插话节奏：攒够随机阈值条新消息且过了冷却才允许回复。 */
    private static final class ChimeInCadence {
        private int messagesSinceReply = 0;
        private int nextMessageThreshold = 0;
        private long lastReplyAt = 0L;

        synchronized void recordMessage() {
            messagesSinceReply++;
        }

        synchronized boolean canReply(int minMessages, int maxMessages, long cooldownMs) {
            ensureThreshold(minMessages, maxMessages);
            long now = System.currentTimeMillis();
            return messagesSinceReply >= nextMessageThreshold && now - lastReplyAt >= cooldownMs;
        }

        synchronized void markReply(int minMessages, int maxMessages) {
            messagesSinceReply = 0;
            lastReplyAt = System.currentTimeMillis();
            nextMessageThreshold = randomThreshold(minMessages, maxMessages);
        }

        synchronized long lastReplyAt() {
            return lastReplyAt;
        }

        private void ensureThreshold(int minMessages, int maxMessages) {
            if (nextMessageThreshold <= 0 || nextMessageThreshold < minMessages || nextMessageThreshold > maxMessages) {
                nextMessageThreshold = randomThreshold(minMessages, maxMessages);
            }
        }

        private static int randomThreshold(int minMessages, int maxMessages) {
            if (maxMessages <= minMessages) return minMessages;
            return ThreadLocalRandom.current().nextInt(minMessages, maxMessages + 1);
        }
    }

    /**
     * 每群的注意力：被 @ / 被点名 / 命中兴趣会涨，每分钟衰减。
     * <p>注意力越高 → 冷却越短、回复概率越大（像真人的"今天心情好，多聊两句"）。
     */
    private static final class Attention {
        private double score = 0;
        private long lastUpdate = System.currentTimeMillis();

        synchronized void add(double delta) {
            long now = System.currentTimeMillis();
            decayTo(now);
            score = Math.min(200, score + delta);
            lastUpdate = now;
        }

        synchronized double current() {
            long now = System.currentTimeMillis();
            decayTo(now);
            lastUpdate = now;
            return score;
        }

        private void decayTo(long now) {
            long mins = (now - lastUpdate) / 60_000L;
            if (mins > 0) {
                score = Math.max(0, score - 5 * mins);
                lastUpdate = now;
            }
        }
    }
}
