package org.windy.xingtubot.module.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群级对话上下文：记录整个群的最近消息（不限于 @机器人 的），
 * 让 AI 能"旁听"群聊语境，像群成员一样理解上下文。
 *
 * <p>与 {@link AiChatMemory}（AI回复的多轮记忆）互补：
 * - GroupContextMemory：群聊全量旁听（所有人的消息，只读上下文）
 * - AiChatMemory：AI 自己的回复记忆（与用户@的多轮对话）
 */
public final class GroupContextMemory {

    /** 每个群保留的最近消息条数 */
    private static final int MAX_CONTEXT = 30;

    private final ConcurrentHashMap<String, List<CtxMessage>> groups = new ConcurrentHashMap<>();

    /** 记录一条群消息（任何人发的，包括机器人自己） */
    public void record(String guildId, String username, String content) {
        if (guildId == null || content == null || content.isEmpty()) return;
        List<CtxMessage> list = groups.computeIfAbsent(guildId, k -> Collections.synchronizedList(new ArrayList<>()));
        list.add(new CtxMessage(username, content));
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

    /** 群消息记录 */
    public static final class CtxMessage {
        public final String username; // QQ昵称，可能null
        public final String content;

        public CtxMessage(String username, String content) {
            this.username = username;
            this.content = content;
        }

        @Override
        public String toString() {
            return (username != null ? username : "???") + "：" + content;
        }
    }
}
