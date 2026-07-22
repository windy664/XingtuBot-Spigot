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
 * GitHub 项目追踪服务。
 * 轮询 GitHub/Gitee API，检测新 release/commit/issue/PR 并回调 listener。
 */
public class GithubTrackerService {

    private final BotLogger logger;
    private final File watchedFile;
    private final File stateFile;
    private int pollIntervalSeconds = 300;
    private List<String> mirrors = new ArrayList<>();

    private final Map<String, WatchedRepo> watched = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> seenReleaseIds = new ConcurrentHashMap<>();
    private final Map<String, String> seenCommitSha = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> seenIssueIds = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> seenPrIds = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private ChangeListener listener;
    private GithubSeenStore seenStore;

    public interface ChangeListener {
        void onNewRelease(String owner, String repo, String tagName, String name, String url);
        void onNewCommit(String owner, String repo, String branch, String sha, String message, String author, String url);
        void onNewIssue(String owner, String repo, int number, String title, String action,
                        String author, String labels, String body, String url);
        void onNewPr(String owner, String repo, int number, String title, String action,
                     String author, String body, String url);
    }

    public GithubTrackerService(BotLogger logger, File dataDir) {
        this.logger = logger;
        this.watchedFile = new File(dataDir, "github-watched.yml");
        this.stateFile = new File(dataDir, "github-state.yml");
    }

    public void setPollIntervalSeconds(int sec) { this.pollIntervalSeconds = sec; }
    public void setMirrors(List<String> mirrors) { this.mirrors = mirrors != null ? mirrors : new ArrayList<>(); }
    public void setChangeListener(ChangeListener listener) { this.listener = listener; }
    public void setSeenStore(GithubSeenStore store) { this.seenStore = store; }

    public void start() {
        loadWatched();
        loadState();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "github-tracker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 10, pollIntervalSeconds, TimeUnit.SECONDS);
        logger.info("[Github] 追踪服务已启动（已加载 " + watched.size() + " 个仓库）");
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        saveWatched();
        saveState();
    }

    public String watch(String owner, String repo, boolean gitee) {
        return watch(owner, repo, gitee, null);
    }

    /**
     * 订阅一个仓库。先验证仓库（和分支）是否存在。
     * @return null=成功，非null=错误信息
     */
    public String watch(String owner, String repo, boolean gitee, String branch) {
        String effectiveBranch = (branch != null && !branch.isEmpty()) ? branch : null;
        String key = repoKey(owner, repo, gitee, effectiveBranch);
        if (watched.containsKey(key)) return "already";

        // 验证仓库是否存在
        WatchedRepo probe = new WatchedRepo(owner, repo);
        probe.gitee = gitee;
        String verifyError = verifyRepo(probe, effectiveBranch);
        if (verifyError != null) return verifyError;

        WatchedRepo wr = new WatchedRepo(owner, repo);
        wr.gitee = gitee;
        wr.branch = effectiveBranch;
        if (gitee) {
            wr.watchIssues = false;
            wr.watchPrs = false;
        }
        watched.put(key, wr);
        initSeenState(key);
        saveWatched();
        saveState();
        return null;
    }

    public boolean unwatch(String owner, String repo, boolean gitee) {
        return unwatch(owner, repo, gitee, null);
    }

    public boolean unwatch(String owner, String repo, boolean gitee, String branch) {
        String effectiveBranch = (branch != null && !branch.isEmpty()) ? branch : null;
        String key = repoKey(owner, repo, gitee, effectiveBranch);
        if (watched.remove(key) == null) return false;
        seenReleaseIds.remove(key);
        seenCommitSha.remove(key);
        seenIssueIds.remove(key);
        seenPrIds.remove(key);
        saveWatched();
        saveState();
        return true;
    }

    public List<WatchedRepo> listWatched() { return new ArrayList<>(watched.values()); }

    /** 验证仓库（和可选分支）是否存在。null=存在，非null=错误信息。 */
    private String verifyRepo(WatchedRepo probe, String branch) {
        // 1. 验证仓库
        try {
            int code = probeApi(probe.apiBase() + "/repos/" + probe.path(), probe.gitee);
            if (code == 404) {
                return (probe.gitee ? "Gitee" : "GitHub") + " 仓库 " + probe.path() + " 不存在";
            }
            if (code >= 400) {
                return "验证仓库失败 (HTTP " + code + ")，请稍后再试";
            }
        } catch (Exception e) {
            return "验证仓库时网络异常: " + e.getMessage();
        }
        // 2. 验证分支（如果指定了）
        if (branch != null && !branch.isEmpty()) {
            try {
                int code = probeApi(probe.apiBase() + "/repos/" + probe.path() + "/branches/" + branch, probe.gitee);
                if (code == 404) {
                    return "分支 " + branch + " 在 " + probe.path() + " 中不存在";
                }
                if (code >= 400) {
                    return "验证分支失败 (HTTP " + code + ")，请稍后再试";
                }
            } catch (Exception e) {
                return "验证分支时网络异常: " + e.getMessage();
            }
        }
        return null;
    }

    /** 发 HEAD/GET 请求拿状态码，不读 body。 */
    private int probeApi(String urlStr, boolean gitee) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "XingtuBot-GithubTracker");
        conn.setRequestProperty("Accept", gitee ? "application/json" : "application/vnd.github.v3+json");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private static String repoKey(String owner, String repo, boolean gitee) {
        return repoKey(owner, repo, gitee, null);
    }

    private static String repoKey(String owner, String repo, boolean gitee, String branch) {
        String base = (gitee ? "gitee:" : "") + owner + "/" + repo;
        if (branch != null && !branch.isEmpty()) base += ":" + branch;
        return base;
    }

    private void poll() {
        boolean stateChanged = false;
        for (WatchedRepo wr : watched.values()) {
            String key = repoKey(wr.owner, wr.repo, wr.gitee, wr.branch);
            if (wr.watchReleases) stateChanged |= safePoll(() -> pollReleases(key, wr), key, "Releases");
            if (wr.watchCommits) stateChanged |= safePoll(() -> pollCommits(key, wr), key, "Commits");
            if (wr.watchIssues) stateChanged |= safePoll(() -> pollIssues(key, wr), key, "Issues");
            if (wr.watchPrs) stateChanged |= safePoll(() -> pollPullRequests(key, wr), key, "PRs");
        }
        if (stateChanged) saveState();
    }

    private interface PollTask { boolean execute() throws Exception; }

    private boolean safePoll(PollTask task, String key, String type) {
        try {
            return task.execute();
        } catch (Exception e) {
            logger.warn(String.format("[Github] 轮询 %s 的 %s 失败: %s", key, type, e.getMessage()));
            return false;
        }
    }

    private <T> boolean cacheAndCheckNew(Set<T> cache, T id) {
        if (cache.contains(id)) return false;
        cache.add(id);
        if (cache.size() > 50) {
            synchronized (cache) {
                Iterator<T> it = cache.iterator();
                for (int i = 0; i < 10 && it.hasNext(); i++) { it.next(); it.remove(); }
            }
        }
        return true;
    }

    private boolean pollReleases(String key, WatchedRepo wr) throws Exception {
        JsonArray arr = apiGetArray(wr.apiBase() + "/repos/" + wr.path() + "/releases?per_page=5", wr.gitee);
        if (arr == null) return false;
        boolean changed = false;

        Set<String> seen = seenReleaseIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            if (!cacheAndCheckNew(seen, obj.get("id").getAsString())) continue;
            changed = true;

            if (listener != null) {
                String tagName = obj.has("tag_name") ? obj.get("tag_name").getAsString() : "?";
                String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : tagName;
                String url = obj.has("html_url") && !obj.get("html_url").isJsonNull() ? obj.get("html_url").getAsString() : wr.webBase() + "/" + wr.path() + "/releases/tag/" + tagName;
                listener.onNewRelease(wr.owner, wr.repo, tagName, name, url);
            }
        }
        return changed;
    }

    private boolean pollCommits(String key, WatchedRepo wr) throws Exception {
        String commitsUrl = wr.apiBase() + "/repos/" + wr.path() + "/commits?per_page=5";
        if (wr.branch != null && !wr.branch.isEmpty()) commitsUrl += "&sha=" + wr.branch;
        JsonArray arr = apiGetArray(commitsUrl, wr.gitee);
        if (arr == null || arr.size() == 0) return false;

        String latestSha = arr.get(0).getAsJsonObject().get("sha").getAsString();
        String prevSha = seenCommitSha.get(key);
        if (latestSha.equals(prevSha)) return false;

        seenCommitSha.put(key, latestSha);
        if (prevSha == null) return true;

        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String sha = obj.get("sha").getAsString();
            if (sha.equals(prevSha)) break;

            if (listener != null) {
                JsonObject commit = obj.getAsJsonObject("commit");
                String msg = commit.has("message") && !commit.get("message").isJsonNull() ? commit.get("message").getAsString() : "";
                String author = "?";
                if (commit.has("author") && commit.get("author").isJsonObject()) {
                    JsonObject a = commit.getAsJsonObject("author");
                    if (a.has("name") && !a.get("name").isJsonNull()) author = a.get("name").getAsString();
                }
                String url = obj.has("html_url") && !obj.get("html_url").isJsonNull() ? obj.get("html_url").getAsString() : wr.webBase() + "/" + wr.path() + "/commit/" + sha;
                listener.onNewCommit(wr.owner, wr.repo, wr.branch, sha.substring(0, 7), msg, author, url);
            }
        }
        return true;
    }

    private boolean pollIssues(String key, WatchedRepo wr) throws Exception {
        JsonArray arr = apiGetArray(wr.apiBase() + "/repos/" + wr.path() + "/issues?state=all&per_page=5&sort=created", wr.gitee);
        if (arr == null) return false;
        boolean changed = false;

        Set<Integer> seen = seenIssueIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("pull_request")) continue;
            int num = obj.get("number").getAsInt();
            if (!cacheAndCheckNew(seen, num)) continue;
            changed = true;

            if (listener != null) {
                String title = obj.get("title").getAsString();
                String state = obj.get("state").getAsString();
                String url = obj.has("html_url") ? obj.get("html_url").getAsString() : wr.webBase() + "/" + wr.path() + "/issues/" + num;
                String author = "";
                if (obj.has("user") && obj.get("user").isJsonObject()) {
                    JsonObject user = obj.getAsJsonObject("user");
                    if (user.has("login") && !user.get("login").isJsonNull()) author = user.get("login").getAsString();
                }
                String labels = "";
                if (obj.has("labels") && obj.get("labels").isJsonArray()) {
                    List<String> names = new ArrayList<>();
                    for (JsonElement le : obj.getAsJsonArray("labels")) {
                        if (le.isJsonObject() && le.getAsJsonObject().has("name")) {
                            names.add(le.getAsJsonObject().get("name").getAsString());
                        }
                    }
                    labels = String.join(", ", names);
                }
                String body = "";
                if (obj.has("body") && !obj.get("body").isJsonNull()) {
                    body = obj.get("body").getAsString();
                }
                listener.onNewIssue(wr.owner, wr.repo, num, title, state, author, labels, body, url);
            }
        }
        return changed;
    }

    private boolean pollPullRequests(String key, WatchedRepo wr) throws Exception {
        JsonArray arr = apiGetArray(wr.apiBase() + "/repos/" + wr.path() + "/pulls?state=all&per_page=5&sort=created", wr.gitee);
        if (arr == null) return false;
        boolean changed = false;

        Set<Integer> seen = seenPrIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            int num = obj.get("number").getAsInt();
            if (!cacheAndCheckNew(seen, num)) continue;
            changed = true;

            if (listener != null) {
                String title = obj.get("title").getAsString();
                String state = obj.get("state").getAsString();
                String url = obj.has("html_url") ? obj.get("html_url").getAsString() : wr.webBase() + "/" + wr.path() + "/pull/" + num;
                String author = "";
                if (obj.has("user") && obj.get("user").isJsonObject()) {
                    JsonObject user = obj.getAsJsonObject("user");
                    if (user.has("login") && !user.get("login").isJsonNull()) author = user.get("login").getAsString();
                }
                String body = "";
                if (obj.has("body") && !obj.get("body").isJsonNull()) {
                    body = obj.get("body").getAsString();
                }
                listener.onNewPr(wr.owner, wr.repo, num, title, state, author, body, url);
            }
        }
        return changed;
    }

    /** 首次订阅时，把当前最新的 release/commit/issue/PR 全部标记为已见，防止刷屏。 */
    private void initSeenState(String key) {
        WatchedRepo wr = watched.get(key);
        if (wr == null) return;
        try {
            JsonArray releases = apiGetArray(wr.apiBase() + "/repos/" + wr.path() + "/releases?per_page=5", wr.gitee);
            if (releases != null && releases.size() > 0) {
                Set<String> seen = seenReleaseIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
                for (JsonElement el : releases) seen.add(el.getAsJsonObject().get("id").getAsString());
            }
        } catch (Exception ignored) {}
        try {
            String commitsInitUrl = wr.apiBase() + "/repos/" + wr.path() + "/commits?per_page=1";
            if (wr.branch != null && !wr.branch.isEmpty()) commitsInitUrl += "&sha=" + wr.branch;
            JsonArray commits = apiGetArray(commitsInitUrl, wr.gitee);
            if (commits != null && commits.size() > 0) {
                seenCommitSha.put(key, commits.get(0).getAsJsonObject().get("sha").getAsString());
            }
        } catch (Exception ignored) {}
        if (wr.watchIssues) {
            try {
                JsonArray issues = apiGetArray(wr.apiBase() + "/repos/" + wr.path() + "/issues?state=all&per_page=5&sort=created", wr.gitee);
                if (issues != null) {
                    Set<Integer> seen = seenIssueIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
                    for (JsonElement el : issues) {
                        JsonObject obj = el.getAsJsonObject();
                        if (obj.has("pull_request")) continue;
                        seen.add(obj.get("number").getAsInt());
                    }
                }
            } catch (Exception ignored) {}
        }
        if (wr.watchPrs) {
            try {
                JsonArray prs = apiGetArray(wr.apiBase() + "/repos/" + wr.path() + "/pulls?state=all&per_page=5&sort=created", wr.gitee);
                if (prs != null) {
                    Set<Integer> seen = seenPrIds.computeIfAbsent(key, k -> Collections.synchronizedSet(new LinkedHashSet<>()));
                    for (JsonElement el : prs) seen.add(el.getAsJsonObject().get("number").getAsInt());
                }
            } catch (Exception ignored) {}
        }
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
                        // key 格式: owner/repo 或 owner/repo:branch
                        String[] parts = path.split("/", 2);
                        if (parts.length == 2) {
                            String owner = parts[0];
                            // repo 部分可能含 :branch
                            String repoAndBranch = parts[1];
                            String repo, branch;
                            int colonIdx = repoAndBranch.indexOf(':');
                            if (colonIdx >= 0) {
                                repo = repoAndBranch.substring(0, colonIdx);
                                branch = repoAndBranch.substring(colonIdx + 1);
                            } else {
                                repo = repoAndBranch;
                                branch = null;
                            }
                            WatchedRepo wr = new WatchedRepo(owner, repo);
                            wr.gitee = gitee;
                            wr.watchReleases = Boolean.TRUE.equals(m.get("releases"));
                            wr.watchCommits = Boolean.TRUE.equals(m.get("commits"));
                            wr.watchIssues = Boolean.TRUE.equals(m.get("issues"));
                            wr.watchPrs = Boolean.TRUE.equals(m.get("prs"));
                            // 分支优先从 key 解析，其次从 value 兼容旧格式
                            if (branch != null && !branch.isEmpty()) {
                                wr.branch = branch;
                            } else {
                                Object branchObj = m.get("branch");
                                if (branchObj instanceof String && !((String) branchObj).isEmpty()) {
                                    wr.branch = (String) branchObj;
                                }
                            }
                            watched.put(repoKey(owner, repo, gitee, wr.branch), wr);
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
            if (wr.branch != null) m.put("branch", wr.branch);
            data.put(repoKey(wr.owner, wr.repo, wr.gitee, wr.branch), m);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(watchedFile), StandardCharsets.UTF_8)) {
            new org.yaml.snakeyaml.Yaml().dump(data, w);
        } catch (Exception e) { logger.warn("[Github] 保存 watched 失败: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void loadState() {
        // 优先从 DB 加载
        if (seenStore != null) {
            try {
                seenStore.loadAll(seenReleaseIds, seenCommitSha, seenIssueIds, seenPrIds);
                logger.info("[Github] 已从数据库加载 seen state");
                return;
            } catch (Exception e) {
                logger.warn("[Github] 从数据库加载 state 失败，回退 YAML: " + e.getMessage());
            }
        }
        // 回退 YAML
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
        // 优先写 DB
        if (seenStore != null) {
            try {
                seenStore.saveAll(seenReleaseIds, seenCommitSha, seenIssueIds, seenPrIds);
                return;
            } catch (Exception e) {
                logger.warn("[Github] 写入数据库失败，回退 YAML: " + e.getMessage());
            }
        }
        // 回退 YAML
        Map<String, Object> state = new LinkedHashMap<>();

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
        /** 追踪的分支名，null 表示默认分支。 */
        public String branch = null;

        public WatchedRepo(String owner, String repo) {
            this.owner = owner;
            this.repo = repo;
        }

        public String path() { return owner + "/" + repo; }
        public String apiBase() { return gitee ? "https://gitee.com/api/v5" : "https://api.github.com"; }
        public String webBase() { return gitee ? "https://gitee.com" : "https://github.com"; }
    }
}
