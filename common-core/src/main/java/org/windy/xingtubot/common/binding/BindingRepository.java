package org.windy.xingtubot.common.binding;

import java.util.List;

/**
 * 绑定数据仓库抽象。上层（BindingService）只依赖本接口，不关心底层是
 * JSON / SQLite / MySQL，也不关心是本端直连还是经大脑代理。
 *
 * <p>实现：
 * <ul>
 *   <li>{@link JdbcBindingRepository}：SQLite / MySQL 直连（MySQL 多端并发安全；
 *       SQLite 仅大脑单连，避免多进程锁库）。</li>
 *   <li>JSON 旧实现 {@link BindingStore} 仍保留兼容。</li>
 *   <li>子服「手脚」用经 plugin message 代理的实现（查询转发给大脑）。</li>
 * </ul>
 */
public interface BindingRepository {

    /** 新增/覆盖绑定（同 player 或同 openid 的旧记录会被替换）。 */
    void put(BindingEntry entry);

    /** 解绑某玩家，返回是否删除了记录。 */
    boolean removeByPlayer(String player);

    BindingEntry findByOpenid(String openid);

    BindingEntry findByPlayer(String player);

    default boolean isPlayerBound(String player) {
        return findByPlayer(player) != null;
    }

    /** 按 openid 取玩家名列表（群服互联用）。 */
    List<String> getPlayersByOpenid(String openid);

    /** 全部绑定（用于上报/迁移）。 */
    List<BindingEntry> all();

    /** 关闭底层资源（连接池等），无则空实现。 */
    default void close() {
    }
}
