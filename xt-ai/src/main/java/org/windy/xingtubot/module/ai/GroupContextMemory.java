package org.windy.xingtubot.module.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群级对话上下文：记录整个群的最近消息（不限于 @机器人 的），
 * 让 AI 能"旁听"群聊语境，像群成员一样理解上下文。
 *
 * <p>与 {@link AiChatMemory}（AI回复的多轮记忆）互补：
 * - GroupContextMemory：群聊全量旁听（所有人的消息，只读上下文）
 * - AiChatMemory：AI 自己的回复记忆（与用户@的多轮对话）
 *
 * <p>额外维护每群的消息序号，用于「防抖期间是否被打断」的判断。
 */
public final class GroupContextMemory {

    /** 每个群保留的最近消息条数 */
    private static final int MAX_CONTEXT = 30;

    private final ConcurrentHashMap<String, List<CtxMessage>> groups = new ConcurrentHashMap<>();
    /** 每群的消息序号（单调递增），用于判断"这条消息之后又来了什么"。 */
    private final ConcurrentHashMap<String, AtomicLong> seqByGroup = new ConcurrentHashMap<>();

    /** 记录一条群消息（任何人发的，包括机器人自己） */
    public void record(String guildId, String username, String content) {
        record(guildId, username, content, false);
    }

    /**
     * 记录一条群消息。
     *
     * @param atBot 这条消息是不是 @ 了机器人
     */
    public void record(String guildId, String username, String content, boolean atBot) {
        if (guildId == null || content == null || content.isEmpty()) return;
        List<CtxMessage> list = groups.computeIfAbsent(guildId, k -> Collections.synchronizedList(new ArrayList<>()));
        long seq = seqByGroup.computeIfAbsent(guildId, k -> new AtomicLong()).incrementAndGet();
        list.add(new CtxMessage(username, content, atBot, seq, System.currentTimeMillis()));
        // 裁剪
        while (list.size() > MAX_CONTEXT) {
            list.remove(0);
        }
    }

    /** 获取群上下文（最近 N 条），返回不可变快照 */
    public List<CtxMessage> getSnapshot(String guildId) {
        List<CtxMessage> list = groups.get(guildId);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    /** 群里最近一条消息（可能是别人的），用于判断"这俩在私聊"。 */
    public CtxMessage lastMessage(String guildId) {
        List<CtxMessage> list = groups.get(guildId);
        if (list == null) return null;
        synchronized (list) {
            return list.isEmpty() ? null : list.get(list.size() - 1);
        }
    }

    /**
     * 群里最近是不是很热闹（10 秒内来了 3 条以上）。
     * <p>用来决定防抖等待多久：热闹的群多等一会儿（免得抢话），安静的群少等。
     */
    public boolean isActiveGroup(String guildId) {
        List<CtxMessage> list = groups.get(guildId);
        if (list == null) return false;
        long now = System.currentTimeMillis();
        int recent = 0;
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0 && i >= list.size() - 8; i--) {
                CtxMessage m = list.get(i);
                if (m.timestamp > 0 && now - m.timestamp < 10_000L) recent++;
            }
        }
        return recent >= 3;
    }

    /**
     * 当前消息<b>之前</b>的那条（用于判断"这俩在私聊"与"复读"）。
     * <p>注意不是 {@link #lastMessage(String)}——那个拿到的是当前消息自己，
     * 拿来判重会永远跟自己一样。
     */
    public CtxMessage previousMessage(String guildId) {
        List<CtxMessage> list = groups.get(guildId);
        if (list == null) return null;
        synchronized (list) {
            return list.size() >= 2 ? list.get(list.size() - 2) : null;
        }
    }

    /** 当前消息序号。 */
    public long currentSeq(String guildId) {
        AtomicLong seq = seqByGroup.get(guildId);
        return seq == null ? 0L : seq.get();
    }

    /**
     * 在指定序号之后，是否有新的 {@code @机器人} 消息。
     * <p>防抖等待期间如果有人 @ 了机器人，这次插话就该让位——那条会自己触发回复。
     */
    public boolean hasNewerAt(String guildId, long sinceSeq) {
        List<CtxMessage> list = groups.get(guildId);
        if (list == null) return false;
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                CtxMessage m = list.get(i);
                if (m.seq <= sinceSeq) break;
                if (m.atBot) return true;
            }
        }
        return false;
    }

    /** 群消息记录 */
    public static final class CtxMessage {
        public final String username; // QQ昵称，可能null
        public final String content;
        /** 是否 @ 了机器人 */
        public final boolean atBot;
        /** 该消息在群里的序号 */
        public final long seq;
        /** 到达时间戳（毫秒） */
        public final long timestamp;

        public CtxMessage(String username, String content) {
            this(username, content, false, 0L, 0L);
        }

        public CtxMessage(String username, String content, boolean atBot, long seq) {
            this(username, content, atBot, seq, 0L);
        }

        public CtxMessage(String username, String content, boolean atBot, long seq, long timestamp) {
            this.username = username;
            this.content = content;
            this.atBot = atBot;
            this.seq = seq;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return (username != null ? username : "???") + "：" + content;
        }
    }
}
