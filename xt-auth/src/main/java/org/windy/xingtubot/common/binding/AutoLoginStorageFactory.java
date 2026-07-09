package org.windy.xingtubot.common.binding;

import org.windy.xingtubot.common.config.BotConfig;

import java.io.File;
import java.util.function.Consumer;

/**
 * 按配置创建自动登录信任期仓库，复用与绑定库<b>同一套</b> {@code storage-type}：
 * <ul>
 *   <li>json   → {@link JsonAutoLoginStore}（文件，默认/兜底）</li>
 *   <li>sqlite → 大脑直连 auto_login.db；子服返回 null（自动登录本就只在代理大脑侧）</li>
 *   <li>mysql  → 直连（并发安全）</li>
 * </ul>
 *
 * <p>自动登录逻辑只在代理大脑（Velocity）侧发生，故 sqlite 单端直连不会锁库。
 */
public final class AutoLoginStorageFactory {

    private AutoLoginStorageFactory() {
    }

    public static AutoLoginRepository create(BotConfig config, boolean isBrain, File dataDir,
                                             Consumer<String> logger) {
        String type = config.getString("storage-type", "json").trim().toLowerCase();
        switch (type) {
            case "mysql":
                return JdbcAutoLoginRepository.mysql(
                        config.getString("mysql-host", "127.0.0.1"),
                        config.getInt("mysql-port", 3306),
                        config.getString("mysql-database", "xingtubot"),
                        config.getString("mysql-user", "root"),
                        config.getString("mysql-password", ""),
                        logger);

            case "sqlite":
                if (isBrain) {
                    File db = new File(dataDir, "auto_login.db");
                    return JdbcAutoLoginRepository.sqlite(db.getAbsolutePath(), logger);
                }
                return null; // 子服不直连；自动登录本就只在代理大脑侧

            case "json":
            default:
                return new JsonAutoLoginStore(new File(dataDir, "auto_login.json"), logger);
        }
    }
}
