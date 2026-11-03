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
 * judge 的决策（含攻击名单的 ADMIN_ATTACK）按消息键暂存，供 handle() 取用——
 * matches 与 handle 之间不共享其他状态。
 */
final class ChimeInController {

    static final String NO_REPLY = "NO_REPLY";

    private final AiService chimeInJudgeService;
    private final BotConfig config;
    private final BotLogger logger;
    private final GroupContextMemory groupContext;

    private final ConcurrentHashMap<String, JudgeDecision> pendingJudgeDecisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChimeInCadence> cadenceByGroup = new ConcurrentHashMap<>();

    ChimeInController(AiService chimeInJudgeService, BotConfig config, BotLogger logger,
                      GroupContextMemory groupContext) {
        this.chimeInJudgeService = chimeInJudgeService;
        this.config = config;
        this.logger = logger;
        this.groupContext = groupContext;
    }

    /** matches 与 handle 定位同一条消息的决策键。 */
    static String decisionKey(BotMessageContext event, String msg) {
        String guild = event.getConversationId() == null ? "" : event.getConversationId();
        String sender = event.getSenderId() == null ? "" : event.getSenderId();
        return guild + "#" + sender + "#" + (msg == null ? 0 : msg.hashCode());
    }

    void putDecision(String key, JudgeDecision decision) {
        pendingJudgeDecisions.put(key, decision);
    }

    /** 取走（并移除）matches 阶段暂存的决策；没有则返回 null。 */
    JudgeDecision takeDecision(String key) {
        return pendingJudgeDecisions.remove(key);
    }

    /** 记一条候选消息（攒节奏用）。 */
    void recordCandidate(String guildId) {
        cadence(guildId).recordMessage();
    }

    /** 当前节奏下是否允许普通插话。 */
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

    /** 让轻量模型判断要不要插话；judge 失败时按不插话处理。 */
    JudgeDecision judge(String msg, String guildId) {
        if (chimeInJudgeService == null) return JudgeDecision.noReply();

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
            return JudgeDecision.parse(decision);
        } catch (Exception e) {
            if (logger != null) logger.warn("[AI] chime-in judge failed: " + e.getMessage());
            return JudgeDecision.noReply();
        }
    }

    private ChimeInCadence cadence(String guildId) {
        String key = guildId == null ? "" : guildId;
        return cadenceByGroup.computeIfAbsent(key, k -> new ChimeInCadence());
    }

    /** judge 的三态结论：不回复 / 插话 / 攻击名单触发的管理反击。 */
    static final class JudgeDecision {
        private static final String ADMIN_ATTACK = "ADMIN_ATTACK";
        private static final String CHIME_IN = "CHIME_IN";
        private static final String NO_REPLY_MODE = "NO_REPLY";

        private final String mode;

        private JudgeDecision(String mode) {
            this.mode = mode;
        }

        static JudgeDecision noReply() {
            return new JudgeDecision(NO_REPLY_MODE);
        }

        static JudgeDecision adminAttack() {
            return new JudgeDecision(ADMIN_ATTACK);
        }

        static JudgeDecision parse(String raw) {
            if (raw == null) return noReply();
            String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z_]", "");
            if (normalized.contains(ADMIN_ATTACK)) return new JudgeDecision(ADMIN_ATTACK);
            if (normalized.contains(CHIME_IN)) return new JudgeDecision(CHIME_IN);
            return noReply();
        }

        boolean shouldReply() {
            return !NO_REPLY_MODE.equals(mode);
        }

        boolean isAdminAttack() {
            return ADMIN_ATTACK.equals(mode);
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
