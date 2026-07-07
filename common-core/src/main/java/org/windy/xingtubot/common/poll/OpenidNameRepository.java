package org.windy.xingtubot.common.poll;

import java.util.Map;

/**
 * OpenID → 昵称持久化仓库接口。
 *
 * <p>L1 缓存（ConcurrentHashMap）+ L2 仓库（SQLite/MySQL）双层架构。
 * 缓存挡读，DB 只在 miss 和写入时碰。
 */
public interface OpenidNameRepository {

    /** 加载全部映射（启动时暖缓存用）。 */
    Map<String, String> loadAll();

    /** 写入/更新一条映射（upsert）。 */
    void upsert(String openid, String nickname);
}
