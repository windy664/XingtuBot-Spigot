package org.windy.xingtubot.module.github;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * GitHub 追踪状态的 JDBC 持久化。
 * 复用 xt-github 自身依赖的 JDBC 连接模式（同 JdbcBindingRepository），支持 SQLite / MySQL。
 *
 * <pre>
 * 建表：github_seen(repo TEXT, type TEXT, id TEXT, PRIMARY KEY(repo, type, id))
 * type 枚举：release / commit / issue / pr
 * </pre>
 */
public class GithubSeenStore {

    public enum Dialect { SQLITE, MYSQL }

    private final String url;
    private final String user;
    private final String password;
    private final Dialect dialect;
    private final Consumer<String> logger;

    public GithubSeenStore(Dialect dialect, String url, String user, String password, Consumer<String> logger) {
        this.dialect = dialect;
        this.url = url;
        this.user = user;
        this.password = password;
        this.logger = logger;
        ensureDriver();
        initSchema();
    }

    public static GithubSeenStore sqlite(String dbFilePath, Consumer<String> logger) {
        return new GithubSeenStore(Dialect.SQLITE, "jdbc:sqlite:" + dbFilePath, null, null, logger);
    }

    public static GithubSeenStore mysql(String host, int port, String database,
                                        String user, String password, Consumer<String> logger) {
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true";
        return new GithubSeenStore(Dialect.MYSQL, url, user, password, logger);
    }

    private void ensureDriver() {
        try {
            if (dialect == Dialect.SQLITE) {
                DriverManager.registerDriver(new org.sqlite.JDBC());
            } else {
                DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("注册 github_seen 数据库驱动失败", e);
        }
    }

    private Connection open() throws SQLException {
        if (dialect == Dialect.SQLITE) return DriverManager.getConnection(url);
        return DriverManager.getConnection(url, user, password);
    }

    private void initSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS github_seen ("
                + "repo VARCHAR(256) NOT NULL, "
                + "type VARCHAR(16) NOT NULL, "
                + "id VARCHAR(128) NOT NULL, "
                + "PRIMARY KEY(repo, type, id))";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            log("初始化 github_seen 表失败: " + e.getMessage());
        }
    }

    /**
     * 从 DB 读取所有 seen state，填充到传入的 map 中。
     */
    public void loadAll(Map<String, Set<String>> releaseIds,
                        Map<String, String> commitSha,
                        Map<String, Set<Integer>> issueIds,
                        Map<String, Set<Integer>> prIds) throws SQLException {
        String sql = "SELECT repo, type, id FROM github_seen";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String repo = rs.getString("repo");
                String type = rs.getString("type");
                String id = rs.getString("id");
                switch (type) {
                    case "release":
                        releaseIds.computeIfAbsent(repo, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                                .add(id);
                        break;
                    case "commit":
                        commitSha.put(repo, id);
                        break;
                    case "issue":
                        try {
                            issueIds.computeIfAbsent(repo, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                                    .add(Integer.parseInt(id));
                        } catch (NumberFormatException ignored) {}
                        break;
                    case "pr":
                        try {
                            prIds.computeIfAbsent(repo, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                                    .add(Integer.parseInt(id));
                        } catch (NumberFormatException ignored) {}
                        break;
                }
            }
        }
    }

    /**
     * 全量写入 seen state（先清后写，事务内完成）。
     */
    public void saveAll(Map<String, Set<String>> releaseIds,
                        Map<String, String> commitSha,
                        Map<String, Set<Integer>> issueIds,
                        Map<String, Set<Integer>> prIds) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM github_seen")) {
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO github_seen(repo, type, id) VALUES(?,?,?)")) {
                // releases
                for (Map.Entry<String, Set<String>> e : releaseIds.entrySet()) {
                    for (String id : e.getValue()) {
                        ins.setString(1, e.getKey());
                        ins.setString(2, "release");
                        ins.setString(3, id);
                        ins.addBatch();
                    }
                }
                // commits
                for (Map.Entry<String, String> e : commitSha.entrySet()) {
                    ins.setString(1, e.getKey());
                    ins.setString(2, "commit");
                    ins.setString(3, e.getValue());
                    ins.addBatch();
                }
                // issues
                for (Map.Entry<String, Set<Integer>> e : issueIds.entrySet()) {
                    for (int id : e.getValue()) {
                        ins.setString(1, e.getKey());
                        ins.setString(2, "issue");
                        ins.setString(3, String.valueOf(id));
                        ins.addBatch();
                    }
                }
                // prs
                for (Map.Entry<String, Set<Integer>> e : prIds.entrySet()) {
                    for (int id : e.getValue()) {
                        ins.setString(1, e.getKey());
                        ins.setString(2, "pr");
                        ins.setString(3, String.valueOf(id));
                        ins.addBatch();
                    }
                }
                ins.executeBatch();
            }
            c.commit();
        }
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }
}
