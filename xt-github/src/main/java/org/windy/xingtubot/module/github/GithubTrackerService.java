package org.windy.xingtubot.module.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GitHub 项目追踪服务：定期轮询 GitHub API，检测 release/commit/issue/PR 变化。
 * 优化：增加了缓存状态的持久化（github-state.yml），解决重启后历史数据丢失导致的重复刷屏问题。
 */
public class GithubTrackerService {

    private final BotLogger logger;
    private final File watchedFile;
    private final File stateFile;
    private int pollIntervalSeconds = 300;
    private List<String> mirrors = new ArrayList<>();

    private final Map<String, WatchedRepo> watched = new ConcurrentHashMap<>();

    // 追踪缓存，用于持久化记忆
    private final Map<String, Set<String>> seenReleaseIds = new ConcurrentHashMap<>();
    private final Map<String, String> seenCommitSha = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> seenIssueIds = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> seenPrIds = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private ChangeListener listener;

    public interface ChangeListener {
        void onNewRelease(String owner, String repo, String tagName, String name, String url);
        void onNewCommit(String owner, String repo, String sha, String message, String author, String url);
        void onNewIssue(String owner, String repo, int number, String title, String action, String url);
        void onNewPr(String owner, String repo, int number, String title, String action, String url);
    }

    public GithubTrackerService(BotLogger logger, File dataDir) {
        this.logger = logger;
        this.watchedFile = new File(dataDir, "github-watched.yml");
        this.stateFile = new File(dataDir, "github-state.yml");
    }

    public void setPollIntervalSeconds(int sec) { this.pollIntervalSeconds = sec; }
    public void setMirrors(List<String> mirrors) { this.mirrors = mirrors != null ? mirrors : new ArrayList<>(); }
    public void setChangeListener(ChangeListener listener) { this.listener = listener; }

    public void start() {
        loadWatched();
        loadState(); // 加载历史记忆
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "github-tracker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 10, pollIntervalSeconds, TimeUnit.SECONDS);
        logger.info("[Github] 追踪服务已启动（内存已恢复，监控 " + watched.size() + " 个仓库）");
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        saveWatched();
        saveState(); // 停机时保存记忆
    }

    public boolean watch(String owner, String repo) {
        return watch(owner, repo, false);
    }

    public boolean watchGitee(String owner, String repo) {
        return watch(owner, repo, true);
    }

    public boolean watch(String owner, String repo, boolean gitee) {
        String key = repoKey(owner, repo, gitee);
        if (watched.containsKey(key)) return false;
        WatchedRepo wr = new WatchedRepo(owner, repo);
        wr.gitee = gitee;
        if (gitee) {
            wr.watchIssues = false;
            wr.watchPrs = false;
        }
        watched.put(key, wr);
        initSeenState(key);
        saveWatched();
        saveState(); // 立刻保存记忆
        return true;
    }

    private static String repoKey(String owner, String repo, boolean gitee) {
        return (gitee ? "gitee:" : "") + owner + "/" + repo;
    }

    public boolean unwatch(String owner, String repo) {
        return unwatch(owner, repo, false);
    }

    public boolean unwatch(String owner, String repo, boolean gitee) {
        String key = repoKey(owner, repo, gitee);
        WatchedRepo removed = watched.remove(key);
        if (removed == null) return false;
        seenReleaseIds.remove(key);
        seenCommitSha.remove(key);
        seenIssueIds.remove(key);
        seenPrIds.remove(key);
        saveWatched();
        saveState();
        return true;
    }

    public List<WatchedRepo> listWatched() {
        return new ArrayList<>(watched.values());
    }

    private void poll() {
        boolean stateChanged = false;
        for (WatchedRepo wr : watched.values()) {
            String key = repoKey(wr.owner, wr.repo, wr.gitee);

            if (wr.watchReleases) {
                try { if (pollReleases(key, wr)) stateChanged = true; }
                catch (Exception e) { logger.warn("[Github] 轮询 " + key + " Releases 失败: " + e.getMessage()); }
            }
            if (wr.watchCommits) {
                try { if (pollCommits(key, wr)) stateChanged = true; }
                catch (Exception e) { logger.warn("[Github] 轮询 " + key + " Commits 失败: " + e.getMessage()); }
            }
            if (wr.watchIssues) {
                try { if (pollIssues(key, wr)) stateChanged = true; }
                catch (Exception e) { logger.warn("[Github] 轮询 " + key + " Issues 失败: " + e.getMessage()); }
            }
            if (wr.watchPrs) {
                try { if (pollPullRequests(key, wr)) stateChanged = true; }
                catch (Exception e) { logger.warn("[Github] 轮询 " + key + " PRs 失败: " + e.getMessage()); }
            }
        }
        if (stateChanged) saveState(); // 如果有更新，定时刷新到磁盘
    }

    private static String repoPath(WatchedRepo wr) {
        return wr.owner + "/" + wr.repo;
    }

    // 返回 boolean 表示是否有新数据（触发 state 更新）
    private boolean pollReleases(String key, WatchedRepo wr) throws Exception {
        JsonArray arr = apiGetArray(wr.apiBase() + "/repos/" + repoPath(wr) + "/releases?per_page=5", wr.gitee);
        if (arr == null) return false;
        boolean changed = false;

        Set<String> seen = seenReleaseIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String id = obj.get("id").getAsString();
            if (seen.contains(id)) continue;
            seen.add(id);
            changed = true;

            if (seen.size() > 50) {
                synchronized (seen) {
                    Iterator<String> it = seen.iterator();
                    for (int i = 0; i < 10 && it.hasNext(); i++) { it.next(); it.remove(); }
                }
            }
            if (listener != null) {
                String tagName = obj.has("tag_name") ? obj.get("tag_name").getAsString() : "?";
                String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : tagName;
                String url = obj.has("html_url") && !obj.get("html_url").isJsonNull()
                        ? obj.get("html_url").getAsString()
                        : wr.webBase() + "/" + repoPath(wr) + "/releases/tag/" + tagName;
                listener.onNewRelease(wr.owner, wr.repo, tagName, name, url);
            }
        }
        return changed;
    }

    private boolean pollCommits(String key, WatchedRepo wr) throws Exception {
        JsonArray arr = apiGetArray(wr.apiBase() + "/repos/" + repoPath(wr) + "/commits?per_page=5", wr.gitee);
        if (arr == null || arr.size() == 0) return false;
        String latestSha = arr.get(0).getAsJsonObject().get("sha").getAsString();
        String prevSha = seenCommitSha.get(key);
        if (latestSha.equals(prevSha)) return false;

        seenCommitSha.put(key, latestSha);
        if (prevSha == null) return true; // 首次记录

        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String sha = obj.get("sha").getAsString();
            if (sha.equals(prevSha)) break;
            if (listener != null) {
                JsonObject commit = obj.getAsJsonObject("commit");
                String message = commit.has("message") && !commit.get("message").isJsonNull()
                        ? commit.get("message").getAsString() : "";
                String author = "?";
                if (commit.has("author") && commit.get("author").isJsonObject()) {
                    JsonObject a = commit.getAsJsonObject("author");
                    if (a.has("name") && !a.get("name").isJsonNull()) author = a.get("name").getAsString();
                }
                String url = obj.has("html_url") && !obj.get("html_url").isJsonNull()
                        ? obj.get("html_url").getAsString()
                        : wr.webBase() + "/" + repoPath(wr) + "/commit/" + sha;
                listener.onNewCommit(wr.owner, wr.repo, sha.substring(0, 7), message, author, url);
            }
        }
        return true;
    }

    private boolean pollIssues(String key, WatchedRepo wr) throws Exception {
        JsonArray arr = apiGetArray(wr.apiBase() + "/repos/" + repoPath(wr) + "/issues?state=all&per_page=5&sort=created", wr.gitee);
        if (arr == null) return false;
        boolean changed = false;

        Set<Integer> seen = seenIssueIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("pull_request")) continue;
            int num = obj.get("number").getAsInt();
            if (seen.contains(num)) continue;
            seen.add(num);
            changed = true;

            if (seen.size() > 50) {
                synchronized (seen) {
                    Iterator<Integer> it = seen.iterator();
                    for (int i = 0; i < 10 && it.hasNext(); i++) { it.next(); it.remove(); }
                }
            }
            if (listener != null) {
                String title = obj.get("title").getAsString();
                String state = obj.get("state").getAsString();
                String url = obj.has("html_url") ? obj.get("html_url").getAsString() : wr.webBase() + "/" + repoPath(wr) + "/issues/" + num;
                listener.onNewIssue(wr.owner, wr.repo, num, title, state, url);
            }
        }
        return changed;
    }

    private boolean pollPullRequests(String key, WatchedRepo wr) throws Exception {
        JsonArray arr = apiGetArray(wr.apiBase() + "/repos/" + repoPath(wr) + "/pulls?state=all&per_page=5&sort=created", wr.gitee);
        if (arr == null) return false;
        boolean changed = false;

        Set<Integer> seen = seenPrIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            int num = obj.get("number").getAsInt();
            if (seen.contains(num)) continue;
            seen.add(num);
            changed = true;

            if (seen.size() > 50) {
                synchronized (seen) {
                    Iterator<Integer> it = seen.iterator();
                    for (int i = 0; i < 10 && it.hasNext(); i++) { it.next(); it.remove(); }
                }
            }
            if (listener != null) {
                String title = obj.get("title").getAsString();
                String state = obj.get("state").getAsString();
                String url = obj.has("html_url") ? obj.get("html_url").getAsString() : wr.webBase() + "/" + repoPath(wr) + "/pull/" + num;
                listener.onNewPr(wr.owner, wr.repo, num, title, state, url);
            }
        }
        return changed;
    }

    private void initSeenState(String key) {
        WatchedRepo wr = watched.get(key);
        if (wr == null) return;
        try {
            JsonArray releases = apiGetArray(wr.apiBase() + "/repos/" + repoPath(wr) + "/releases?per_page=1", wr.gitee);
            if (releases != null && releases.size() > 0) {
                Set<String> seen = seenReleaseIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
                for (JsonElement el : releases) seen.add(el.getAsJsonObject().get("id").getAsString());
            }
        } catch (Exception ignored) {}
        try {
            JsonArray commits = apiGetArray(wr.apiBase() + "/repos/" + repoPath(wr) + "/commits?per_page=1", wr.gitee);
            if (commits != null && commits.size() > 0) {
                seenCommitSha.put(key, commits.get(0).getAsJsonObject().get("sha").getAsString());
            }
        } catch (Exception ignored) {}
    }

    private JsonArray apiGetArray(String urlStr, boolean gitee) throws Exception {
        if (gitee) return tryFetch(urlStr, true);
        for (String mirror : mirrors) {
            if (mirror == null || mirror.isEmpty()) continue;
            String mirrored = mirror.endsWith("/") ? mirror + urlStr : mirror + "/" + urlStr;
            try {
                JsonArray result = tryFetch(mirrored, false);
                if (result != null) return result;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private JsonArray tryFetch(String urlStr, boolean gitee) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "XingtuBot-GithubTracker");
        conn.setRequestProperty("Accept", gitee ? "application/json" : "application/vnd.github.v3+json");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        if (conn.getResponseCode() != 200) return null;
        try (Reader r = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonArray();
        }
    }

    // ===== 持久化与状态记忆 (代替数据库) =====

    @SuppressWarnings("unchecked")
    private void loadWatched() {
        if (!watchedFile.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(watchedFile), StandardCharsets.UTF_8)) {
            Object parsed = new org.yaml.snakeyaml.Yaml().load(r);
            if (parsed instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) parsed;
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    String key = entry.getKey();
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) entry.getValue();
                        boolean gitee = key.startsWith("gitee:") || Boolean.TRUE.equals(m.get("gitee"));
                        String path = key.startsWith("gitee:") ? key.substring("gitee:".length()) : key;
                        String[] parts = path.split("/", 2);
                        if (parts.length == 2) {
                            WatchedRepo wr = new WatchedRepo(parts[0], parts[1]);
                            wr.gitee = gitee;
                            wr.watchReleases = Boolean.TRUE.equals(m.get("releases"));
                            wr.watchCommits = Boolean.TRUE.equals(m.get("commits"));
                            wr.watchIssues = Boolean.TRUE.equals(m.get("issues"));
                            wr.watchPrs = Boolean.TRUE.equals(m.get("prs"));
                            watched.put(repoKey(parts[0], parts[1], gitee), wr);
                        }
                    }
                }
            }
        } catch (Exception e) { logger.warn("[Github] 加载 watched 失败: " + e.getMessage()); }
    }

    private void saveWatched() {
        Map<String, Object> data = new LinkedHashMap<>();
        for (WatchedRepo wr : watched.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gitee", wr.gitee);
            m.put("releases", wr.watchReleases);
            m.put("commits", wr.watchCommits);
            m.put("issues", wr.watchIssues);
            m.put("prs", wr.watchPrs);
            data.put(repoKey(wr.owner, wr.repo, wr.gitee), m);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(watchedFile), StandardCharsets.UTF_8)) {
            new org.yaml.snakeyaml.Yaml().dump(data, w);
        } catch (Exception e) { logger.warn("[Github] 保存 watched 失败: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void loadState() {
        if (!stateFile.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(stateFile), StandardCharsets.UTF_8)) {
            Map<String, Object> state = new org.yaml.snakeyaml.Yaml().load(r);
            if (state == null) return;

            if (state.containsKey("releases")) {
                ((Map<String, List<String>>) state.get("releases")).forEach((k, v) ->
                        seenReleaseIds.put(k, Collections.synchronizedSet(new LinkedHashSet<>(v))));
            }
            if (state.containsKey("commits")) {
                seenCommitSha.putAll((Map<String, String>) state.get("commits"));
            }
            if (state.containsKey("issues")) {
                ((Map<String, List<Integer>>) state.get("issues")).forEach((k, v) ->
                        seenIssueIds.put(k, Collections.synchronizedSet(new LinkedHashSet<>(v))));
            }
            if (state.containsKey("prs")) {
                ((Map<String, List<Integer>>) state.get("prs")).forEach((k, v) ->
                        seenPrIds.put(k, Collections.synchronizedSet(new LinkedHashSet<>(v))));
            }
        } catch (Exception e) { logger.warn("[Github] 加载 state 失败: " + e.getMessage()); }
    }

    private void saveState() {
        Map<String, Object> state = new LinkedHashMap<>();

        // 转换 Set 为 List 以便 YAML 序列化
        Map<String, List<String>> relMap = new LinkedHashMap<>();
        seenReleaseIds.forEach((k, v) -> relMap.put(k, new ArrayList<>(v)));
        state.put("releases", relMap);

        state.put("commits", seenCommitSha);

        Map<String, List<Integer>> issueMap = new LinkedHashMap<>();
        seenIssueIds.forEach((k, v) -> issueMap.put(k, new ArrayList<>(v)));
        state.put("issues", issueMap);

        Map<String, List<Integer>> prMap = new LinkedHashMap<>();
        seenPrIds.forEach((k, v) -> prMap.put(k, new ArrayList<>(v)));
        state.put("prs", prMap);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(stateFile), StandardCharsets.UTF_8)) {
            new org.yaml.snakeyaml.Yaml().dump(state, w);
        } catch (Exception e) { logger.warn("[Github] 保存 state 失败: " + e.getMessage()); }
    }

    public static class WatchedRepo {
        public final String owner;
        public final String repo;
        public boolean gitee = false;
        public boolean watchReleases = true;
        public boolean watchCommits = true;
        public boolean watchIssues = true;
        public boolean watchPrs = true;

        public WatchedRepo(String owner, String repo) {
            this.owner = owner;
            this.repo = repo;
        }

        public String apiBase() { return gitee ? "https://gitee.com/api/v5" : "https://api.github.com"; }
        public String webBase() { return gitee ? "https://gitee.com" : "https://github.com"; }
    }
}