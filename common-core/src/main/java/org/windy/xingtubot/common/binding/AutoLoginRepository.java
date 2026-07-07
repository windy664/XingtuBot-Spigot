package org.windy.xingtubot.common.binding;

/**
 * 「IP 绑定的自动登录」信任期持久化仓库。
 *
 * <p>玩家登录后正常退出时按 {@code (player → ip, 过期时刻)} 记一条；同 IP 在窗口内重进即免密自动登录。
 * 原实现存于内存 Map，重启即失效；本仓库把它落到与绑定库同一后端（storage-type: json/sqlite/mysql），
 * 使信任期<b>跨重启存活</b>，且大玩家量下走 DB 不必全量读写。
 *
 * <p>操作低频（仅玩家进服读 / 退服写），实现可每次开关连接，无需常驻缓存。
 */
public interface AutoLoginRepository {

    /** 信任记录：上次登录退出时的 IP + 过期时刻（毫秒时间戳）。 */
    final class Entry {
        public final String ip;
        public final long expiry;
        public Entry(String ip, long expiry) {
            this.ip = ip;
            this.expiry = expiry;
        }
    }

    /** 写入/覆盖某玩家的信任记录（player 大小写不敏感，实现内部统一小写）。 */
    void put(String player, String ip, long expiry);

    /** 取某玩家的信任记录；无则返回 null。不在此处判过期（由调用方比时间）。 */
    Entry get(String player);

    /** 删除某玩家的信任记录（过期清理 / 主动失效）。 */
    void remove(String player);
}
