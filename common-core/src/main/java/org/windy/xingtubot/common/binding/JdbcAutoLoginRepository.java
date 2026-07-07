package org.windy.xingtubot.common.binding;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * JDBC 实现的自动登录信任期仓库，SQLite 与 MySQL 共用（与 {@link JdbcBindingRepository} 同模式）。
 *
 * <p>每次操作开/关连接（低频，量级不大）。MySQL 多端并发安全；SQLite 仅由代理大脑单端使用。
 * 表 {@code auto_login(player, ip, expiry)}，player 主键。
 */
public class JdbcAutoLoginRepository implements AutoLoginRepository {

    public enum Dialect { SQLITE, MYSQL }

    private final String url;
    private final String user;
    private final String password;
    private final Dialect dialect;
    private final Consumer<String> logger;

    public JdbcAutoLoginRepository(Dialect dialect, String url, String user, String password,
                                   Consumer<String> logger) {
        this.dialect = dialect;
        this.url = url;
        this.user = user;
        this.password = password;
        this.logger = logger;
        ensureDriver();
        initSchema();
    }

    public static JdbcAutoLoginRepository sqlite(String dbFilePath, Consumer<String> logger) {
        return new JdbcAutoLoginRepository(Dialect.SQLITE, "jdbc:sqlite:" + dbFilePath, null, null, logger);
    }

    public static JdbcAutoLoginRepository mysql(String host, int port, String database,
                                                String user, String password, Consumer<String> logger) {
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true";
        return new JdbcAutoLoginRepository(Dialect.MYSQL, url, user, password, logger);
    }

    private void ensureDriver() {
        // 显式注册驱动（Velocity/Bungee 插件隔离 classloader 下 ServiceLoader 自动发现不生效）。
        try {
            if (dialect == Dialect.SQLITE) {
                DriverManager.registerDriver(new org.sqlite.JDBC());
            } else {
                DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("注册数据库驱动失败（请确认对应 JDBC 驱动已打入插件）", e);
        }
    }

    private Connection open() throws SQLException {
        if (dialect == Dialect.SQLITE) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, password);
    }

    private void initSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS auto_login ("
                + "player VARCHAR(64) PRIMARY KEY, "
                + "ip VARCHAR(64), "
                + "expiry BIGINT)";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            log("初始化自动登录表失败: " + e.getMessage());
        }
    }

    @Override
    public void put(String player, String ip, long expiry) {
        String key = player.toLowerCase();
        try (Connection c = open()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM auto_login WHERE player = ?")) {
                del.setString(1, key);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO auto_login(player, ip, expiry) VALUES(?,?,?)")) {
                ins.setString(1, key);
                ins.setString(2, ip);
                ins.setLong(3, expiry);
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            log("写入自动登录记录失败: " + e.getMessage());
        }
    }

    @Override
    public Entry get(String player) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT ip, expiry FROM auto_login WHERE player = ?")) {
            ps.setString(1, player.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Entry(rs.getString("ip"), rs.getLong("expiry"));
            }
        } catch (SQLException e) {
            log("查询自动登录记录失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void remove(String player) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auto_login WHERE player = ?")) {
            ps.setString(1, player.toLowerCase());
            ps.executeUpdate();
        } catch (SQLException e) {
            log("删除自动登录记录失败: " + e.getMessage());
        }
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }
}
