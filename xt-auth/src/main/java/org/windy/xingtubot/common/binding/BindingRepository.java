package org.windy.xingtubot.common.binding;

import java.util.List;

/**
 * 绑定数据仓库抽象。上层（BindingService）只依赖本接口，不关心底层是
 * JSON / SQLite / MySQL，也不关心是本端直连还是经大脑代理。
 */
public interface BindingRepository {

    void put(BindingEntry entry);

    boolean removeByPlayer(String player);

    BindingEntry findByOpenid(String openid);

    BindingEntry findByPlayer(String player);

    default boolean isPlayerBound(String player) {
        return findByPlayer(player) != null;
    }

    List<String> getPlayersByOpenid(String openid);

    List<BindingEntry> all();

    default void close() {
    }
}
