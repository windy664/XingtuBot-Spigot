package org.windy.xingtubot.common.binding;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JDBC 实现的绑定仓库，SQLite 与 MySQL 共用（SQL 基本通用）。
 *
 * <p>每次操作开/关连接（简单可靠，量级不大）。MySQL 多端并发安全；
 * SQLite 多进程并发写会锁库，故 SQLite 仅由「大脑」单端使用（上层据 storage-type 决定）。
 *
 * <p>驱动通过反射加载，未打入对应驱动时给出清晰报错，避免硬依赖打包失败。
 */
public class JdbcBindingRepository implements BindingRepository {

    public enum Dialect { SQLITE, MYSQL }

    private final String url;
    private final String user;
    private final String password;
    private final Dialect dialect;
    private final Consumer<String> logger;

    public JdbcBindingRepository(Dialect dialect, String url, String user, String password,
                                 Consumer<String> logger) {
        this.dialect = dialect;
        this.url = url;
        this.user = user;
        this.password = password;
        this.logger = logger;
        ensureDriver();
        initSchema();
    }

    /** 工厂：SQLite（文件路径）。 */
    public static JdbcBindingRepository sqlite(String dbFilePath, Consumer<String> logger) {
        return new JdbcBindingRepository(Dialect.SQLITE, "jdbc:sqlite:" + dbFilePath, null, null, logger);
    }

    /** 工厂：MySQL。 */
    public static JdbcBindingRepository mysql(String host, int port, String database,
                                              String user, String password, Consumer<String> logger) {
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true";
        return new JdbcBindingRepository(Dialect.MYSQL, url, user, password, logger);
    }

    private void ensureDriver() {
        // 直接 new 驱动实例并注册（非反射）：DriverManager 的 ServiceLoader 自动发现在
        // Velocity/Bungee 插件隔离 classloader 下不生效，显式注册才稳。
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
        // openid 唯一、player 唯一；两种方言通用的建表语句
        String sql = "CREATE TABLE IF NOT EXISTS binding ("
                + "player VARCHAR(64) PRIMARY KEY, "
                + "openid VARCHAR(128), "
                + "qq VARCHAR(20), "
                + "time VARCHAR(32))";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
            // openid 唯一索引（IF NOT EXISTS 两方言均支持）
            try (PreparedStatement idx = c.prepareStatement(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_binding_openid ON binding(openid)")) {
                idx.executeUpdate();
            }
        } catch (SQLException e) {
            log("初始化绑定表失败: " + e.getMessage());
        }
    }

    @Override
    public void put(BindingEntry entry) {
        // 先删可能冲突的旧记录（同 player 或同 openid），再插入
        try (Connection c = open()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM binding WHERE player = ? OR openid = ?")) {
                del.setString(1, entry.player);
                del.setString(2, entry.openid);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO binding(player, openid, qq, time) VALUES(?,?,?,?)")) {
                ins.setString(1, entry.player);
                ins.setString(2, entry.openid);
                ins.setString(3, entry.qq);
                ins.setString(4, entry.time);
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            log("写入绑定失败: " + e.getMessage());
        }
    }

    @Override
    public boolean removeByPlayer(String player) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM binding WHERE player = ?")) {
            ps.setString(1, player);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log("删除绑定失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public BindingEntry findByOpenid(String openid) {
        return queryOne("SELECT player, openid, qq, time FROM binding WHERE openid = ?", openid);
    }

    @Override
    public BindingEntry findByPlayer(String player) {
        return queryOne("SELECT player, openid, qq, time FROM binding WHERE player = ?", player);
    }

    private BindingEntry queryOne(String sql, String param) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return read(rs);
            }
        } catch (SQLException e) {
            log("查询绑定失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<String> getPlayersByOpenid(String openid) {
        List<String> result = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT player FROM binding WHERE openid = ?")) {
            ps.setString(1, openid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log("查询玩家列表失败: " + e.getMessage());
        }
        return result;
    }

    @Override
    public List<BindingEntry> all() {
        List<BindingEntry> result = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT player, openid, qq, time FROM binding");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(read(rs));
        } catch (SQLException e) {
            log("读取全部绑定失败: " + e.getMessage());
        }
        return result;
    }

    private BindingEntry read(ResultSet rs) throws SQLException {
        BindingEntry e = new BindingEntry();
        e.player = rs.getString("player");
        e.openid = rs.getString("openid");
        e.qq = rs.getString("qq");
        e.time = rs.getString("time");
        return e;
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }
}
