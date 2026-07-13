package org.windy.xingtubot.common.binding;

/**
 * 「IP 绑定的自动登录」信任期持久化仓库。
 */
public interface AutoLoginRepository {

    final class Entry {
        public final String ip;
        public final long expiry;
        public Entry(String ip, long expiry) {
            this.ip = ip;
            this.expiry = expiry;
        }
    }

    void put(String player, String ip, long expiry);

    Entry get(String player);

    void remove(String player);
}
