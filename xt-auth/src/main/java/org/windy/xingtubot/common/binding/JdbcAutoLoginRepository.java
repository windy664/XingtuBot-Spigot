package org.windy.xingtubot.common.binding;
import org.windy.xingtubot.common.binding.*;

import java.sql.*;
import java.util.function.Consumer;

/**
 * SQLite 实现的自动登录信任期仓库。
 * 每次操作开/关连接（低频，量级不大）。仅由代理大脑单端使用。
 */
public class JdbcAutoLoginRepository implements AutoLoginRepository {

    private final String url;
    private final Consumer<String> logger;

    private JdbcAutoLoginRepository(String url, Consumer<String> logger) {
        this.url = url;
        this.logger = logger;
        try {
            DriverManager.registerDriver(new org.sqlite.JDBC());
        } catch (SQLException e) {
            throw new IllegalStateException("注册 sqlite 驱动失败", e);
        }
        initSchema();
    }

    public static JdbcAutoLoginRepository sqlite(String dbFilePath, Consumer<String> logger) {
        return new JdbcAutoLoginRepository("jdbc:sqlite:" + dbFilePath, logger);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url);
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
