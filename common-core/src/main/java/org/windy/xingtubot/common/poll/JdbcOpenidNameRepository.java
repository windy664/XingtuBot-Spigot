package org.windy.xingtubot.common.poll;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenID → 昵称 JDBC 仓库（SQLite / MySQL 通用）。
 *
 * <p>表结构：{@code openid_names(openid VARCHAR PK, nickname VARCHAR, updated_at BIGINT)}
 * <p>SQLite 用 WAL 模式，读写并发无压力。
 */
public class JdbcOpenidNameRepository implements OpenidNameRepository {

    private final String jdbcUrl;
    private final Consumer<String> logger;

    private JdbcOpenidNameRepository(String jdbcUrl, Consumer<String> logger) {
        this.jdbcUrl = jdbcUrl;
        this.logger = logger;
    }

    public static JdbcOpenidNameRepository sqlite(String dbPath, Consumer<String> logger) {
        // 直接 new 驱动实例注册（非反射）：DriverManager 的 ServiceLoader 自动发现依赖调用方
        // classloader，在 Velocity/Bungee 插件隔离 classloader 下不生效（表现为 No suitable driver found）。
        try {
            DriverManager.registerDriver(new org.sqlite.JDBC());
        } catch (SQLException e) {
            if (logger != null) logger.accept("[OpenidName] 注册 sqlite 驱动失败: " + e.getMessage());
        }
        String url = "jdbc:sqlite:" + dbPath;
        JdbcOpenidNameRepository repo = new JdbcOpenidNameRepository(url, logger);
        repo.initTable();
        return repo;
    }

    public static JdbcOpenidNameRepository mysql(String host, int port, String database,
                                                  String user, String password, Consumer<String> logger) {
        try {
            DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
        } catch (SQLException e) {
            if (logger != null) logger.accept("[OpenidName] 注册 mysql 驱动失败: " + e.getMessage());
        }
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&autoReconnect=true&characterEncoding=UTF-8";
        JdbcOpenidNameRepository repo = new JdbcOpenidNameRepository(url, logger) {
            @Override
            protected Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }
        };
        repo.initTable();
        return repo;
    }

    protected Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initTable() {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            // SQLite 和 MySQL 都兼容的建表语句
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS openid_names ("
                    + "  openid VARCHAR(128) PRIMARY KEY,"
                    + "  nickname VARCHAR(256) NOT NULL,"
                    + "  updated_at BIGINT NOT NULL"
                    + ")");
            if (logger != null) logger.accept("[OpenidName] 数据库表已就绪");
        } catch (SQLException e) {
            if (logger != null) logger.accept("[OpenidName] 建表失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, String> loadAll() {
        Map<String, String> map = new HashMap<>();
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT openid, nickname FROM openid_names")) {
            while (rs.next()) {
                map.put(rs.getString("openid"), rs.getString("nickname"));
            }
            if (logger != null) logger.accept("[OpenidName] 从 DB 加载 " + map.size() + " 条");
        } catch (SQLException e) {
            if (logger != null) logger.accept("[OpenidName] 加载失败: " + e.getMessage());
        }
        return map;
    }

    @Override
    public void upsert(String openid, String nickname) {
        // SQLite 用 INSERT OR REPLACE，MySQL 用 INSERT ... ON DUPLICATE KEY UPDATE
        String sql = jdbcUrl.contains("sqlite")
                ? "INSERT OR REPLACE INTO openid_names (openid, nickname, updated_at) VALUES (?, ?, ?)"
                : "INSERT INTO openid_names (openid, nickname, updated_at) VALUES (?, ?, ?) "
                  + "ON DUPLICATE KEY UPDATE nickname=VALUES(nickname), updated_at=VALUES(updated_at)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, openid);
            ps.setString(2, nickname);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (logger != null) logger.accept("[OpenidName] upsert 失败: " + e.getMessage());
        }
    }
}
