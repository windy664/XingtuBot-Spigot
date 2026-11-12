package org.windy.xingtubot.module.ai;

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
 * 自主参与（chime-in）控制：普通群消息先由轻量模型判断要不要接话，
 * 再用「攒消息数 + 随机阈值 + 冷却」的节奏限制控制插话频率。
 *
 * <h3>线程模型</h3>
 * {@link #judge(String, String)} 会发网络请求，<b>只能在 AI 专属线程池里调用</b>。
 * {@link #recordCandidate(String)} 与 {@link #canReply(String)} 是纯计算，
 * 放在 matches() 阶段（QQ 网关线程）执行没有问题。
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
        return cadence(guildId).canReply(minMessages, maxMessages, cooldownMs);
    }

    /** 普通插话已回复，重置节奏并摇下一个随机阈值。 */
    void markReplied(String guildId) {
        int minMessages = Math.max(1, config.getInt("chime-in-min-messages", 3));
        int maxMessages = Math.max(minMessages, config.getInt("chime-in-max-messages", 6));
        cadence(guildId).markReply(minMessages, maxMessages);
    }

    /**
     * 让轻量模型判断要不要插话。judge 失败时按不插话处理（fail-closed）。
     *
     * <p><b>会发网络请求，只能在 AI 线程池里调用。</b>
     *
     * @return true = 要插话，false = 不插话
     */
    boolean judge(String msg, String guildId) {
        if (chimeInJudgeService == null) return false;

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(AiPromptBuilder.message("system",
                "你是群聊情商判断器。判断要不要插话，只回复：CHIME_IN 或 NO_REPLY。\n\n" +
                "CHIME_IN 的情况：\n" +
                "- 话题跟你有关（你之前说过的话、你了解的领域、你帮得上忙）\n" +
                "- 有人在求助/提问，你能给出有用的回复\n" +
                "- 气氛轻松，你说句话能接住话题、加点趣味或共鸣\n" +
                "- 有人说了个有趣的事，你有自然的反应想说\n\n" +
                "NO_REPLY 的情况：\n" +
                "- 话题是两个人之间的私事、你插不上嘴\n" +
                "- 聊得很热烈了，你挤进去只会打断节奏\n" +
                "- 你没什么想说的，硬接话会很尬\n" +
                "- 话题敏感（政治/色情/争议），不该参与\n" +
                "- 最近几条消息节奏很快（刷屏/斗图），不适合插话\n\n" +
                "你在群里的名字是「" + BotRuntimeState.getBotName() + "」；群聊记录里以它开头的消息是你自己发的，不算别人说话。"));

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
                "最新消息=" + msg + "\n你要不要插话？只回复 CHIME_IN 或 NO_REPLY。"));

        try {
            String decision = chimeInJudgeService.chat(messages);
            return parse(decision);
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] chime-in judge failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 解析 judge 的输出。
     * <p>只认 CHIME_IN / NO_REPLY 两个词；<b>不再解析 ADMIN_ATTACK</b>——
     * 旧版虽然定义了它，但攻击模式实际只由 {@code attack-targets} 名单触发，
     * 模型从来产不出这个值，保留只会造成误判。
     */
    private static boolean parse(String raw) {
        if (raw == null) return false;
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z_]", "");
        // 先判 NO_REPLY，避免 "NO_REPLY" 里含 "REPLY" 被误当成要插话
        if (normalized.contains("NO_REPLY") || normalized.contains("NOREPLY")) return false;
        return normalized.contains("CHIME_IN") || normalized.contains("CHIMEIN");
    }

    private ChimeInCadence cadence(String guildId) {
        String key = guildId == null ? "" : guildId;
        return cadenceByGroup.computeIfAbsent(key, k -> new ChimeInCadence());
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
}
