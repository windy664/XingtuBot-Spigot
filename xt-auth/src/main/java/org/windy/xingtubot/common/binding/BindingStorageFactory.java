package org.windy.xingtubot.common.binding;

import org.windy.xingtubot.common.config.BotConfig;

import java.io.File;
import java.util.function.Consumer;

/**
 * 按配置创建绑定仓库（方案甲：SQLite 仅大脑直连避免锁库，MySQL 各端直连并发安全）。
 *
 * <p>storage-type:
 * <ul>
 *   <li>json   → {@link BindingStore}（文件，默认/兜底）</li>
 *   <li>sqlite → 大脑：直连 db 文件；子服：返回 null（应改用经大脑代理的仓库）</li>
 *   <li>mysql  → 各端直连（并发安全）</li>
 * </ul>
 */
public final class BindingStorageFactory {

    private BindingStorageFactory() {
    }

    /**
     * @param isBrain   当前端是否为大脑（Velocity 主导端 / 单机 Spigot 本地端）
     * @param dataDir   数据目录（json / sqlite 文件落在此处）
     * @return 仓库；若返回 null 表示该端 SQLite 模式下应使用大脑代理仓库（由调用方处理）
     */
    public static BindingRepository create(BotConfig config, boolean isBrain, File dataDir,
                                           Consumer<String> logger) {
        String type = config.getString("storage-type", "json").trim().toLowerCase();
        switch (type) {
            case "mysql":
                return JdbcBindingRepository.mysql(
                        config.getString("mysql-host", "127.0.0.1"),
                        config.getInt("mysql-port", 3306),
                        config.getString("mysql-database", "xingtubot"),
                        config.getString("mysql-user", "root"),
                        config.getString("mysql-password", ""),
                        logger);

            case "sqlite":
                if (isBrain) {
                    File db = new File(dataDir, "binding.db");
                    return JdbcBindingRepository.sqlite(db.getAbsolutePath(), logger);
                }
                // 子服 SQLite：不直连（会锁库），交由调用方接入大脑代理仓库
                return null;

            case "json":
            default:
                return new BindingStore(new File(dataDir, "binding.json"), logger);
        }
    }
}
