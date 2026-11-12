package org.windy.xingtubot.module.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群友画像：让 AI「认识」这群人，而不是把每条消息都当陌生人。
 *
 * <h3>只存事实，不编故事</h3>
 * 画像里记的是<b>确实发生过的事</b>：谁说过多少次话、上次说了什么。
 * 这些素材注入提示词后，模型可以自然地提起前情（「上次你也说这个」）；
 * 反过来，如果只给模型一个「你们很熟」的暗示而没有实际素材，
 * 它只会编出根本没发生过的对话——那比不熟更糟。
 *
 * <p>画像<b>按群隔离</b>（键是 群#用户），不同群的人际关系不会串味。
 */
final class UserProfile {

    /** 单个群友的画像。 */
    static final class Entry {
        volatile String nickname = "";
        volatile int talkCount = 0;
        volatile long lastSeen = 0L;
        /** 上次说过的话（截断），供模型自然引用。 */
        volatile String lastMessage = "";
        /** 亲密度：说得越多、被机器人回得越多越高。 */
        volatile int affinity = 0;
    }

    /** 上次发言保留多少字，避免提示词膨胀。 */
    private static final int LAST_MESSAGE_MAX = 40;

    private final ConcurrentHashMap<String, Entry> byKey = new ConcurrentHashMap<>();

    private static String key(String guildId, String userId) {
        return (guildId == null ? "" : guildId) + "#" + (userId == null ? "" : userId);
    }

    /**
     * 记录一次发言。
     *
     * @param replyFromBot 这条消息是不是机器人回给该用户的（用于累加亲密度）
     */
    void record(String guildId, String userId, String nickname, String message, boolean replyFromBot) {
        if (guildId == null || userId == null) return;
        Entry e = byKey.computeIfAbsent(key(guildId, userId), k -> new Entry());
        if (nickname != null && !nickname.trim().isEmpty()) {
            e.nickname = nickname.trim();
        }
        e.talkCount++;
        e.lastSeen = System.currentTimeMillis();
        if (message != null && !message.trim().isEmpty()) {
            String m = message.trim();
            e.lastMessage = m.length() > LAST_MESSAGE_MAX ? m.substring(0, LAST_MESSAGE_MAX) + "…" : m;
        }
        if (replyFromBot) {
            e.affinity += 5;
        }
    }

    /** 机器人回某人时加一点亲密度。 */
    void markReplied(String guildId, String userId) {
        if (guildId == null || userId == null) return;
        Entry e = byKey.get(key(guildId, userId));
        if (e != null) e.affinity += 5;
    }

    /**
     * 渲染成提示词里的一段。
     *
     * @param maxEntries 最多列几个人
     */
    String renderForPrompt(String guildId, int maxEntries) {
        if (guildId == null || maxEntries <= 0) return "";
        String prefix = guildId + "#";
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<String, Entry> en : byKey.entrySet()) {
            if (!en.getKey().startsWith(prefix)) continue;
            list.add(en.getValue());
        }
        if (list.isEmpty()) return "";
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                // 亲密度优先，其次发言数
                if (a.affinity != b.affinity) return b.affinity - a.affinity;
                return b.talkCount - a.talkCount;
            }
        });

        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Entry e : list) {
            if (n++ >= maxEntries) break;
            if (e.nickname.isEmpty() && e.lastMessage.isEmpty()) continue;
            sb.append("- ");
            sb.append(e.nickname.isEmpty() ? "某位群友" : e.nickname);
            sb.append("（说过 ").append(e.talkCount).append(" 次");
            if (!e.lastMessage.isEmpty()) {
                sb.append("，上次说「").append(e.lastMessage).append("」");
            }
            sb.append("）\n");
        }
        if (sb.length() == 0) return "";
        return "【你认识的群友】\n" + sb
                + "你可以自然地提起上面这些人的近况（别硬扯，也别编没发生过的事）。\n";
    }
}
