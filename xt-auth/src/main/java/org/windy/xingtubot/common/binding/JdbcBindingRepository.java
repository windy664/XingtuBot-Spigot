package org.windy.xingtubot.common.binding;
import org.windy.xingtubot.common.binding.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SQLite 实现的绑定仓库。
 * 每次操作开/关连接（简单可靠，量级不大）。仅由「大脑」单端使用。
 */
public class JdbcBindingRepository implements BindingRepository {

    private final String url;
    private final Consumer<String> logger;

    private JdbcBindingRepository(String url, Consumer<String> logger) {
        this.url = url;
        this.logger = logger;
        try {
            DriverManager.registerDriver(new org.sqlite.JDBC());
        } catch (SQLException e) {
            throw new IllegalStateException("注册 sqlite 驱动失败", e);
        }
        initSchema();
    }

    public static JdbcBindingRepository sqlite(String dbFilePath, Consumer<String> logger) {
        return new JdbcBindingRepository("jdbc:sqlite:" + dbFilePath, logger);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void initSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS binding ("
                + "player VARCHAR(64) PRIMARY KEY, "
                + "openid VARCHAR(128), "
                + "qq VARCHAR(20), "
                + "time VARCHAR(32))";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
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
