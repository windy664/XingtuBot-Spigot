package org.windy.xingtubot.common.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.module.capability.ProactiveSender;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.queue.PendingMessageQueue;
import org.windy.xingtubot.common.util.Http;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组更新监控服务（平台无关）。
 * 支持两种数据源：Modrinth（已发布版本）和 GitHub（分支 commit）。
 * 与 {@link McmodApiService} 同级，共用同一套 HTTP/代理/日志模式。
 */
public class ModUpdateService {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GITEE_API = "https://gitee.com/api/v5";
    private static final long CHECK_DELAY_INITIAL = 30L;
    private static final long INTER_REQUEST_DELAY_MS = 2000L;

    private static Proxy proxy = Proxy.NO_PROXY;

    private final BotConfig config;
    private final BotLogger logger;
    private final Consumer<String> notifier;
    private final Translator translator;

    /** key -> WatchEntry（Modrinth 用 slug，GitHub 用 gh:owner/repo:branch） */
    private final Map<String, WatchEntry> watchList = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    private long checkIntervalMinutes = 60;
    private String defaultMcVersion = "1.20.1";
    private String defaultLoader = "forge";
    private String githubToken = "";
    private String giteeToken = "";
    private final List<String> githubMirrors = new ArrayList<>();

    // ==================== Feed 模式（新模组发现） ====================
    private boolean feedEnabled = false;
    private long feedIntervalMinutes = 120;
    private String feedSources = "modrinth";
    private boolean feedModrinthEnabled = true;
    private boolean feedMcmodEnabled = false;
    private String feedCategories = "neoforge";
    private String feedVersions = "26.2";
    private int feedLimit = 20;
    private String mcmodFeedMcver = "26.2";
    private int mcmodFeedPlatform = 1;
    private int mcmodFeedApi = 13;
    private int mcmodFeedLimit = 20;
    private McmodApiService mcmodApi;

    /** 已见过的 Modrinth project_id 集合（Feed 去重用） */
    private final Set<String> seenFeedProjectIds = ConcurrentHashMap.newKeySet();
    private final Set<String> seenMcmodClassIds = ConcurrentHashMap.newKeySet();
    /** 首次运行标记：首次只收集不通知 */
    private volatile boolean feedInitialized = false;
    /** feed 检查计数器（用于控制 feed 检查频率） */
    private final AtomicInteger feedTickCounter = new AtomicInteger(0);
    /** 最近一次 feed 发现的新模组（供 /modwatch feed 查看） */
    private final List<FeedItem> lastFeedResults = new CopyOnWriteArrayList<>();
    /** Feed 通知定向（null/空=走全局，含"*"=推全部群） */
    private List<String> feedNotifyTargets = Collections.emptyList();

    // 注：GitHub / Gitee 仓库追踪（release/commit/issue/PR）已整体迁移到独立的 xt-github（/github 命令）。
    // 原 modquery 内的 GitHub feed 一套（githubFeed*/checkGitHubFeed/GitHubFeedItem 等）已删除，避免两套重复。

    // ==================== 通知定向 ====================
    /** 全局通知目标群 openid 列表（为空则走全局队列） */
    private List<String> notifyTargetGroups = Collections.emptyList();

    // ==================== 持久化 ====================
    /** 数据目录（用于持久化 feed 状态；null = 不持久化） */
    private File dataDir;

    /** 主动消息推送能力（惰性句柄；未就绪/为 null = 回退到 PendingMessageQueue 被动模式）。 */
    private volatile ProactiveSender sender;
    private volatile Supplier<List<String>> coreAllowedGroupsSupplier = () -> Collections.singletonList("*");
    // 模组通知回显到游戏：非 null 时通知同步给在线玩家（平台侧注入，关闭则为 null）。
    private volatile Consumer<String> gameEcho;

    /**
     * 设置主动消息发送器，启用主动消息推送。
     * 就绪时 notifyTargets() 会直接推送，不再依赖被动回复窗口。
     * 未设置/未就绪时行为不变（排队到 PendingMessageQueue）。
     */
    public void setProactiveSender(ProactiveSender sender) {
        this.sender = sender;
    }

    public void setCoreAllowedGroupsSupplier(Supplier<List<String>> coreAllowedGroupsSupplier) {
        this.coreAllowedGroupsSupplier = coreAllowedGroupsSupplier != null
                ? coreAllowedGroupsSupplier : () -> Collections.singletonList("*");
    }

    /** 设置「模组通知回显到游戏」回调（平台侧注入，传 null 关闭）。 */
    public void setGameEcho(Consumer<String> gameEcho) {
        this.gameEcho = gameEcho;
    }

    public ModUpdateService(BotConfig config, BotLogger logger, Consumer<String> notifier) {
        this(config, logger, notifier, null);
    }

    public ModUpdateService(BotConfig config, BotLogger logger, Consumer<String> notifier,
                            Translator translator) {
        this.config = config;
        this.logger = logger;
        this.notifier = notifier;
        this.translator = translator;
    }

    /**
     * 设置数据目录，用于持久化 feed 状态（seenFeedProjectIds / seenGitHubCommits）。
     * 应在 start() 之前调用。
     */
    public void setDataDir(File dataDir) {
        this.dataDir = dataDir;
    }

    public void setMcmodApi(McmodApiService mcmodApi) {
        this.mcmodApi = mcmodApi;
    }

    // ==================== 持久化方法 ====================

    private static final String FEED_STATE_FILE = "modwatch_feed_state.json";
    private static final String WATCH_STATE_FILE = "modwatch_watch_state.json";

    /**
     * 从 dataDir/modwatch_feed_state.json 加载 feed 状态。
     * 文件格式：{"seenProjects":[...],"seenGithubCommits":{"key":"sha",...},"feedInit":true,"githubFeedInit":true}
     */
    /** Modrinth 发现筛选签名：feed-versions + feed-categories。变化即视为换了关注目标，需重置发现基线。 */
    private String feedFilterSig() {
        return "sources=" + (feedSources == null ? "" : feedSources)
                + "|mr=" + (feedVersions == null ? "" : feedVersions)
                + "/" + (feedCategories == null ? "" : feedCategories)
                + "|mcmod=" + mcmodFeedMcver + "/" + mcmodFeedPlatform + "/" + mcmodFeedApi;
    }

    private void loadFeedState() {
        if (dataDir == null) return;
        File file = new File(dataDir, FEED_STATE_FILE);
        if (!file.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString().trim();
            if (json.isEmpty()) return;

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // 加载 seenFeedProjectIds
            if (root.has("seenProjects")) {
                JsonArray arr = root.getAsJsonArray("seenProjects");
                for (int i = 0; i < arr.size(); i++) {
                    String id = arr.get(i).getAsString();
                    seenFeedProjectIds.add(id);
                }
            }
            if (root.has("seenMcmodClasses")) {
                JsonArray arr = root.getAsJsonArray("seenMcmodClasses");
                for (int i = 0; i < arr.size(); i++) {
                    String id = arr.get(i).getAsString();
                    seenMcmodClassIds.add(id);
                }
            }

            // 加载初始化标记
            if (root.has("feedInit")) {
                feedInitialized = root.get("feedInit").getAsBoolean();
            }

            // 筛选条件（feed-versions / feed-categories）变了 → 旧基线对新筛选无意义，
            // 重置 Modrinth 发现基线，让"老 mod 新进目标版本"能被重新当作新发现。
            String savedSig = root.has("feedFilterSig") ? root.get("feedFilterSig").getAsString() : null;
            if (savedSig != null && !savedSig.equals(feedFilterSig())) {
                seenFeedProjectIds.clear();
                seenMcmodClassIds.clear();
                feedInitialized = false;
                info("[Feed] 筛选条件变化(" + savedSig + " → " + feedFilterSig()
                        + ")，已重置新模组发现基线");
            }

            info("已加载 feed 状态: " + seenFeedProjectIds.size() + " 个已知模组");
        } catch (Exception e) {
            warn("加载 feed 状态失败: " + e.getMessage());
        }
    }

    /**
     * 将 feed 状态持久化到 dataDir/modwatch_feed_state.json。
     */
    private void saveFeedState() {
        if (dataDir == null) return;
        if (!dataDir.exists()) dataDir.mkdirs();

        JsonObject root = new JsonObject();

        // seenFeedProjectIds
        JsonArray projects = new JsonArray();
        for (String id : seenFeedProjectIds) {
            projects.add(id);
        }
        root.add("seenProjects", projects);

        JsonArray mcmodClasses = new JsonArray();
        for (String id : seenMcmodClassIds) {
            mcmodClasses.add(id);
        }
        root.add("seenMcmodClasses", mcmodClasses);

        // 初始化标记
        root.addProperty("feedInit", feedInitialized);
        // 筛选签名：feed-versions / feed-categories 变更时用于判定是否重置发现基线
        root.addProperty("feedFilterSig", feedFilterSig());

        File file = new File(dataDir, FEED_STATE_FILE);
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(root.toString());
        } catch (Exception e) {
            warn("保存 feed 状态失败: " + e.getMessage());
        }
    }

    /**
     * 从 dataDir/modwatch_watch_state.json 加载 watchList 运行时状态。
     * 仅恢复 lastVersionId/displayName/lastCheckTime/lastCommitMsg/notifiedBranches，
     * watchList 本身由配置加载，此方法做状态叠加。
     */
    private void loadWatchState() {
        if (dataDir == null) return;
        File file = new File(dataDir, WATCH_STATE_FILE);
        if (!file.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString().trim();
            if (json.isEmpty()) return;

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int restored = 0;
            for (String key : root.keySet()) {
                WatchEntry entry = watchList.get(key);
                if (entry == null) continue; // 配置里已移除的不恢复
                entry.loadFromJson(root.getAsJsonObject(key));
                restored++;
            }
            info("已加载 watch 状态: " + restored + " 个监控项恢复了上次检测数据");
        } catch (Exception e) {
            warn("加载 watch 状态失败: " + e.getMessage());
        }
    }

    /**
     * 将 watchList 运行时状态持久化到 dataDir/modwatch_watch_state.json。
     */
    private void saveWatchState() {
        if (dataDir == null) return;
        if (!dataDir.exists()) dataDir.mkdirs();

        JsonObject root = new JsonObject();
        for (Map.Entry<String, WatchEntry> e : watchList.entrySet()) {
            WatchEntry entry = e.getValue();
            // 只保存已检测过的（有 lastVersionId 的）
            if (entry.getLastVersionId() != null) {
                root.add(e.getKey(), entry.toJson());
            }
        }

        File file = new File(dataDir, WATCH_STATE_FILE);
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(root.toString());
        } catch (Exception e) {
            warn("保存 watch 状态失败: " + e.getMessage());
        }
    }

    // ==================== 生命周期 ====================

    public void start() {
        loadConfig();
        if (!config.getBoolean("modwatch-enable", true)) {
            info("模组更新监控已禁用（modwatch-enable=false）");
            return;
        }
        loadWatchListFromConfig();
        loadWatchState();
        loadFeedState();
        startScheduler();
        info("模组更新监控已启动，监控 " + watchList.size() + " 个模组，间隔 "
                + checkIntervalMinutes + " 分钟");
        if (feedEnabled) {
            info("新模组发现已启用: " + feedSourceSummary()
                    + "，间隔 " + feedIntervalMinutes + " 分钟");
        }
        if (!notifyTargetGroups.isEmpty()) {
            info("通知定向推送: " + notifyTargetGroups.size() + " 个目标群");
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        info("模组更新监控已停止");
    }

    // ==================== 公共操作 ====================

    public String addWatch(String slug, String mcVersion, String loader) {
        if (slug == null || slug.trim().isEmpty()) return "slug 不能为空";
        slug = slug.trim().toLowerCase();
        if (mcVersion == null || mcVersion.isEmpty()) mcVersion = defaultMcVersion;
        if (loader == null || loader.isEmpty()) loader = defaultLoader;

        if (watchList.containsKey(slug)) {
            return "「" + slug + "」已在监控列表中";
        }

        WatchEntry entry = WatchEntry.ofModrinth(slug, mcVersion, loader);
        String err = resolveProject(entry);
        if (err != null) return err;

        String versionErr = fetchModrinthLatest(entry);
        if (versionErr != null) {
            warn("首次查询版本失败（已加入监控）: " + slug + " - " + versionErr);
        }

        watchList.put(slug, entry);
        info("已添加 Modrinth 监控: " + slug + " (" + mcVersion + "/" + loader + ")");
        return null;
    }

    public String addGitHubWatch(String ownerRepo, String branch) {
        if (ownerRepo == null || ownerRepo.trim().isEmpty()) return "owner/repo 不能为空";
        ownerRepo = ownerRepo.trim();
        if (branch == null || branch.trim().isEmpty()) {
            branch = "main";
        } else {
            branch = branch.trim();
        }

        String key = "gh:" + ownerRepo.toLowerCase() + ":" + branch.toLowerCase();
        if (watchList.containsKey(key)) {
            return "「" + ownerRepo + ":" + branch + "」已在监控列表中";
        }

        WatchEntry entry = WatchEntry.ofGitHub(ownerRepo, branch);
        String err = fetchGitHubLatest(entry);
        if (err != null) return err;

        watchList.put(key, entry);
        info("已添加 GitHub 监控: " + ownerRepo + " (分支: " + branch + ")");
        return null;
    }

    public String addGiteeWatch(String ownerRepo, String branch) {
        if (ownerRepo == null || ownerRepo.trim().isEmpty()) return "owner/repo 不能为空";
        ownerRepo = ownerRepo.trim();
        if (branch == null || branch.trim().isEmpty()) {
            branch = "master";
        } else {
            branch = branch.trim();
        }

        String key = "gitee:" + ownerRepo.toLowerCase() + ":" + branch.toLowerCase();
        if (watchList.containsKey(key)) {
            return "「" + ownerRepo + ":" + branch + "」已在监控列表中";
        }

        WatchEntry entry = WatchEntry.ofGitee(ownerRepo, branch);
        String err = fetchGiteeLatest(entry);
        if (err != null) return err;

        watchList.put(key, entry);
        info("已添加 Gitee 监控: " + ownerRepo + " (分支: " + branch + ")");
        return null;
    }

    public boolean removeWatch(String key) {
        if (key == null) return false;
        return watchList.remove(key.trim().toLowerCase()) != null;
    }

    public List<WatchEntry> listWatches() {
        return Collections.unmodifiableList(new ArrayList<>(watchList.values()));
    }

    public WatchEntry getWatch(String key) {
        if (key == null) return null;
        return watchList.get(key.trim().toLowerCase());
    }

    public String checkSingle(String key) {
        WatchEntry entry = watchList.get(key == null ? "" : key.trim().toLowerCase());
        if (entry == null) return "「" + key + "」不在监控列表中";

        String oldId = entry.getLastVersionId();
        String err;
        if (entry.getSource() == WatchEntry.Source.GITHUB) {
            err = fetchGitHubLatest(entry);
        } else if (entry.getSource() == WatchEntry.Source.GITEE) {
            err = fetchGiteeLatest(entry);
        } else {
            err = fetchModrinthLatest(entry);
        }
        if (err != null) return "检查失败: " + err;

        saveWatchState(); // 手动检查后也持久化

        if (oldId != null && !oldId.equals(entry.getLastVersionId())) {
            if (entry.getSource() == WatchEntry.Source.GITHUB || entry.getSource() == WatchEntry.Source.GITEE) {
                return "🆕 有新 commit！\n" + translate(entry.getLastCommitMsg());
            }
            return "🆕 有新版本: " + entry.getLastVersionId();
        }

        if (entry.getSource() == WatchEntry.Source.GITHUB || entry.getSource() == WatchEntry.Source.GITEE) {
            return "✅ 「" + entry.getDisplayName() + "」分支 " + entry.getBranch()
                    + " 最新 commit: " + shortSha(entry.getLastVersionId())
                    + " - " + translate(entry.getLastCommitMsg());
        }
        return "✅ 「" + entry.getDisplayName() + "」当前已是最新版本";
    }

    public void checkAllAsync() {
        if (scheduler != null) {
            scheduler.execute(this::doCheckAll);
        }
    }

    // ==================== Feed 模式（新模组发现） ====================

    /** Feed 发现的新模组数据项 */
    public static class FeedItem {
        public final String source;
        public final String sourceLabel;
        public final String projectId;
        public final String title;
        public final String slug;
        public final String author;
        public final String description;
        public final int downloads;
        public final String dateModified;
        public final String url;
        public final long discoveredAt;

        public FeedItem(String projectId, String title, String slug, String author,
                        String description, int downloads, String dateModified) {
            this("modrinth", "Modrinth", projectId, title, slug, author, description, downloads,
                    dateModified, slug == null || slug.isEmpty() ? "" : "https://modrinth.com/mod/" + slug);
        }

        public FeedItem(String source, String sourceLabel, String projectId, String title, String slug, String author,
                        String description, int downloads, String dateModified, String url) {
            this.source = source;
            this.sourceLabel = sourceLabel;
            this.projectId = projectId;
            this.title = title;
            this.slug = slug;
            this.author = author;
            this.description = description;
            this.downloads = downloads;
            this.dateModified = dateModified;
            this.url = url;
            this.discoveredAt = System.currentTimeMillis();
        }
    }

    /**
     * 向指定目标群投递通知消息。
     * targets 为 null 或空 → 走全局 notifyTargetGroups。
     * targets 包含 "*" → 推送到所有群（走全局队列）。
     * 否则只推给 targets 列表里的群。
     */
    private void notifyTargets(String message, List<String> targets) {
        // 回显到游戏（若开启）：模组通知是机器人主动发到群的消息，同步给在线玩家
        if (gameEcho != null) {
            try {
                gameEcho.accept(message);
            } catch (Exception ignored) {
            }
        }
        List<String> groupIds = org.windy.xingtubot.common.util.GroupTargets.resolveKnownGroups(
                coreAllowedGroupsSupplier.get(), resolveTargetGroups(targets));
        ProactiveSender s = this.sender;
        boolean ready = s != null && s.isReady();

        // 目标为空（* = 全部群）时，用已知群列表展开为真实目标群清单。
        if (ready && !groupIds.isEmpty()) {
            // 主动消息模式：直接推送到各群（默认走 markdown，无原生权限时实现内部回退纯文本）
            for (String groupOpenId : groupIds) {
                if (s.sendGroupMarkdown(groupOpenId, message)) {
                    info("主动推送成功: " + groupOpenId);
                } else {
                    warn("主动推送失败(" + groupOpenId + ")，回退到被动队列");
                    PendingMessageQueue.getInstance().offer(groupOpenId, message);
                }
            }
            return;
        }

        // 无主动发送器 → 被动队列（原逻辑）
        for (String groupOpenId : groupIds) {
            PendingMessageQueue.getInstance().offer(groupOpenId, message);
        }
    }

    /** 解析目标群列表：per-item targets → 全局 notifyTargetGroups → 空（=全部）。 */
    private List<String> resolveTargetGroups(List<String> targets) {
        if (targets != null && targets.stream().anyMatch("*"::equals)) {
            return Collections.emptyList(); // * = 全部群，返回空表示走全局
        }
        if (targets != null && !targets.isEmpty()) {
            return targets;
        }
        return notifyTargetGroups;
    }

    /**
     * 手动触发一次 feed 检查（供命令调用）。
     *
     * @return 发现的新模组数量
     */
    public int checkFeedNow() {
        if (!feedEnabled) return -1;
        return checkFeeds();
    }

    /**
     * 获取最近一次 feed 发现的新模组列表（格式化）。
     */
    public String formatFeedResults() {
        if (!feedEnabled) return "⚠️ 新模组发现功能未启用";
        if (lastFeedResults.isEmpty()) return "📋 暂无新模组发现";

        // 批量翻译简介
        List<String> descs = new ArrayList<>();
        for (FeedItem item : lastFeedResults) {
            String d = item.description != null ? item.description : "";

            d = d.replace("\n", " ").replace("\r", "");

            if (d.length() > 120) d = d.substring(0, 120) + "…";
            descs.add(d);
        }
        boolean translateDescriptions = true;
        for (FeedItem item : lastFeedResults) {
            if ("mcmod".equals(item.source)) {
                translateDescriptions = false;
                break;
            }
        }
        if (translateDescriptions && translator != null && translator.isEnabled()) {
            descs = translator.batchTranslateEnToZh(descs);
        }

        org.windy.xingtubot.common.util.Md card = org.windy.xingtubot.common.util.Md
                .card("🆕", "最近发现的新模组（" + lastFeedResults.size() + " 个）")
                .subtitle(feedSourceSummary());
        for (int i = 0; i < lastFeedResults.size(); i++) {
            FeedItem item = lastFeedResults.get(i);
            String title = (item.title == null || item.title.isEmpty()) ? item.slug : item.title;
            StringBuilder line = new StringBuilder();
            line.append(i + 1).append(". ");
            if (item.url != null && !item.url.isEmpty()) {
                line.append("[**").append(title).append("**](").append(item.url).append(")");
            } else {
                line.append("**").append(title).append("**");
            }
            if (item.sourceLabel != null && !item.sourceLabel.isEmpty()) {
                line.append(" `").append(item.sourceLabel).append("`");
            }
            if (item.author != null && !item.author.isEmpty()) {
                line.append("　`").append(item.author).append("`");
            }
            card.line(line.toString());
            String desc = (i < descs.size() && descs.get(i) != null) ? descs.get(i) : "";
            if (!desc.isEmpty()) {
                card.line("　　" + desc);
            }
        }
        return card.build();
    }

    /**
     * 检查 Modrinth 搜索 API 发现新模组。
     *
     * @return 本次发现的新模组数量
     */
    private int checkFeeds() {
        boolean initializing = !feedInitialized;
        int total = 0;

        if (feedModrinthEnabled) {
            total += checkModrinthFeed(initializing);
        }
        if (feedMcmodEnabled) {
            total += checkMcmodFeed(initializing);
        }

        if (initializing) {
            feedInitialized = true;
            saveFeedState();
            info("[Feed] 首次初始化完成，Modrinth 已知 " + seenFeedProjectIds.size()
                    + " 个，MC百科已知 " + seenMcmodClassIds.size() + " 个");
            return 0;
        }
        return total;
    }

    private int checkMcmodFeed(boolean initializing) {
        if (mcmodApi == null) {
            warn("[Feed/MCMOD] 未配置 MC百科服务，已跳过");
            return 0;
        }
        try {
            List<McmodApiService.LatestModEntry> entries = mcmodApi.listLatestMods(
                    mcmodFeedMcver, mcmodFeedPlatform, mcmodFeedApi, mcmodFeedLimit);
            if (entries.isEmpty()) {
                info("[Feed/MCMOD] 搜索结果为空");
                return 0;
            }

            List<FeedItem> newItems = new ArrayList<>();
            for (McmodApiService.LatestModEntry e : entries) {
                if (e.classId == null || e.classId.isEmpty()) continue;
                if (seenMcmodClassIds.contains(e.classId)) continue;
                seenMcmodClassIds.add(e.classId);

                String title = e.name;
                if (e.ename != null && !e.ename.isEmpty()) {
                    title = title + " (" + e.ename + ")";
                }
                String intro = e.intro == null ? "" : e.intro.trim();
                if (intro.isEmpty()) {
                    intro = mcmodApi.fetchModIntroSummary(e.url, 180);
                }

                newItems.add(new FeedItem(
                        "mcmod",
                        "MC百科",
                        e.classId,
                        title,
                        e.classId,
                        "",
                        intro,
                        0,
                        "",
                        e.url
                ));
            }

            if (initializing) {
                info("[Feed/MCMOD] 首次初始化，已记录 " + seenMcmodClassIds.size() + " 个现有模组");
                return 0;
            }

            if (newItems.isEmpty()) {
                info("[Feed/MCMOD] 无新模组（已知 " + seenMcmodClassIds.size() + " 个）");
                return 0;
            }

            lastFeedResults.addAll(0, newItems);
            while (lastFeedResults.size() > 50) {
                lastFeedResults.remove(lastFeedResults.size() - 1);
            }

            org.windy.xingtubot.common.util.Md card = org.windy.xingtubot.common.util.Md
                    .card("🆕", "MC百科 新模组发现（" + newItems.size() + " 个）")
                    .subtitle(feedSourceSummary());
            appendFeedItems(card, newItems, false);
            card.quote("使用 /modwatch feed 查看详情");

            notifyTargets(card.build(), feedNotifyTargets);
            saveFeedState();
            info("[Feed/MCMOD] 发现 " + newItems.size() + " 个新模组，已投递通知");
            return newItems.size();
        } catch (Exception e) {
            warn("[Feed/MCMOD] 检查失败: " + e.getMessage());
            return 0;
        }
    }

    private String feedSourceSummary() {
        List<String> parts = new ArrayList<>();
        if (feedModrinthEnabled) {
            parts.add("Modrinth: " + feedCategories + " / " + feedVersions);
        }
        if (feedMcmodEnabled) {
            parts.add("MC百科: " + mcmodFeedMcver + " / " + firstFeedLoader());
        }
        return parts.isEmpty() ? "来源：未配置" : "来源：" + String.join("；", parts);
    }

    private void appendFeedItems(org.windy.xingtubot.common.util.Md card, List<FeedItem> items,
                                 boolean showDownloads) {
        List<String> descs = new ArrayList<>();
        for (FeedItem item : items) {
            String d = item.description != null ? item.description : "";
            d = d.replace("\n", " ").replace("\r", "");
            if (d.length() > 120) d = d.substring(0, 120) + "…";
            descs.add(d);
        }
        boolean translateDescriptions = true;
        for (FeedItem item : items) {
            if ("mcmod".equals(item.source)) {
                translateDescriptions = false;
                break;
            }
        }
        if (translateDescriptions && translator != null && translator.isEnabled()) {
            descs = translator.batchTranslateEnToZh(descs);
        }

        for (int i = 0; i < items.size(); i++) {
            FeedItem item = items.get(i);
            String title = (item.title == null || item.title.isEmpty()) ? item.slug : item.title;
            StringBuilder line = new StringBuilder();
            line.append(i + 1).append(". ");
            if (item.url != null && !item.url.isEmpty()) {
                line.append("[**").append(title).append("**](").append(item.url).append(")");
            } else {
                line.append("**").append(title).append("**");
            }
            if (item.sourceLabel != null && !item.sourceLabel.isEmpty()) {
                line.append(" `").append(item.sourceLabel).append("`");
            }
            if (item.author != null && !item.author.isEmpty()) {
                line.append("　`").append(item.author).append("`");
            }
            if (showDownloads && item.downloads > 0) {
                line.append("　⬇").append(item.downloads);
            }
            card.line(line.toString());
            String desc = (i < descs.size() && descs.get(i) != null) ? descs.get(i) : "";
            if (!desc.isEmpty()) {
                card.line("　　" + desc);
            }
        }
    }

    private int checkModrinthFeed(boolean initializing) {
        try {
            String facets = buildFeedFacets();
            String url = MODRINTH_API + "/search?query=&index=updated"
                    + "&facets=" + URLEncoder.encode(facets, "UTF-8")
                    + "&limit=" + feedLimit;

            String json = fetchJson(url);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray hits = root.getAsJsonArray("hits");
            if (hits == null || hits.size() == 0) {
                info("[Feed] 搜索结果为空");
                return 0;
            }

            List<FeedItem> newItems = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                JsonObject h = hits.get(i).getAsJsonObject();
                String projectId = optStr(h, "project_id");
                if (projectId.isEmpty()) continue;

                if (seenFeedProjectIds.contains(projectId)) continue;
                seenFeedProjectIds.add(projectId);

                FeedItem item = new FeedItem(
                        projectId,
                        optStr(h, "title"),
                        optStr(h, "slug"),
                        optStr(h, "author"),
                        optStr(h, "description"),
                        h.has("downloads") ? h.get("downloads").getAsInt() : 0,
                        optStr(h, "date_modified")
                );
                newItems.add(item);
            }

            if (initializing) {
                // 首次运行：只收集，不通知
                info("[Feed] 首次初始化完成，已记录 " + seenFeedProjectIds.size() + " 个现有模组");
                return 0;
            }

            if (newItems.isEmpty()) {
                info("[Feed] 无新模组（已知 " + seenFeedProjectIds.size() + " 个）");
                return 0;
            }

            // 更新最近发现列表（保留最近 50 个）
            lastFeedResults.addAll(0, newItems);
            while (lastFeedResults.size() > 50) {
                lastFeedResults.remove(lastFeedResults.size() - 1);
            }

            // 批量翻译简介
            List<String> descs = new ArrayList<>();
            for (FeedItem item : newItems) {
                String d = item.description != null ? item.description : "";

                d = d.replace("\n", " ").replace("\r", "");

                if (d.length() > 120) d = d.substring(0, 120) + "…";
                descs.add(d);
            }
            if (translator != null && translator.isEnabled()) {
                descs = translator.batchTranslateEnToZh(descs);
            }

            // 生成通知消息（markdown 卡片，与 mcmod / github 通知同一套审美）
            org.windy.xingtubot.common.util.Md card = org.windy.xingtubot.common.util.Md
                    .card("🆕", "Modrinth 新模组发现（" + newItems.size() + " 个）")
                    .subtitle(feedSourceSummary());
            for (int i = 0; i < newItems.size(); i++) {
                FeedItem item = newItems.get(i);
                String title = (item.title == null || item.title.isEmpty()) ? item.slug : item.title;
                StringBuilder line = new StringBuilder();
                line.append(i + 1).append(". ");
                if (item.url != null && !item.url.isEmpty()) {
                    line.append("[**").append(title).append("**](").append(item.url).append(")");
                } else {
                    line.append("**").append(title).append("**");
                }
                if (item.sourceLabel != null && !item.sourceLabel.isEmpty()) {
                    line.append(" `").append(item.sourceLabel).append("`");
                }
                if (item.author != null && !item.author.isEmpty()) {
                    line.append("　`").append(item.author).append("`");
                }
                if (item.downloads > 0) {
                    line.append("　⬇").append(item.downloads);
                }
                card.line(line.toString());
                String desc = (i < descs.size() && descs.get(i) != null) ? descs.get(i) : "";
                if (!desc.isEmpty()) {
                    card.line("　　" + desc);
                }
            }
            card.quote("使用 /modwatch feed 查看详情");

            notifyTargets(card.build(), feedNotifyTargets);
            saveFeedState();
            info("[Feed] 发现 " + newItems.size() + " 个新模组，已投递通知");
            return newItems.size();
        } catch (Exception e) {
            warn("[Feed] 检查失败: " + e.getMessage());
            return 0;
        }
    }

    /** 构建 Feed facets JSON 字符串 */
    private String buildFeedFacets() {
        StringBuilder facets = new StringBuilder("[");
        boolean first = true;

        // 分类 facets（OR 关系）
        if (feedCategories != null && !feedCategories.isEmpty()) {
            String[] cats = feedCategories.split(",");
            if (cats.length > 0) {
                StringBuilder catArr = new StringBuilder("[");
                for (int i = 0; i < cats.length; i++) {
                    if (i > 0) catArr.append(",");
                    catArr.append("\"categories:").append(cats[i].trim()).append("\"");
                }
                catArr.append("]");
                facets.append(catArr);
                first = false;
            }
        }

        // 版本 facets
        if (feedVersions != null && !feedVersions.isEmpty()) {
            String[] vers = feedVersions.split(",");
            if (vers.length > 0) {
                if (!first) facets.append(",");
                StringBuilder verArr = new StringBuilder("[");
                for (int i = 0; i < vers.length; i++) {
                    if (i > 0) verArr.append(",");
                    verArr.append("\"versions:").append(vers[i].trim()).append("\"");
                }
                verArr.append("]");
                facets.append(verArr);
                first = false;
            }
        }

        // 项目类型
        if (!first) facets.append(",");
        facets.append("[\"project_type:mod\"]");

        facets.append("]");
        return facets.toString();
    }

    /**
     * 获取指定监控项的最近 commit 列表（仅 GitHub 源）。
     *
     * @param key   监控项 key（slug 或 gh:owner/repo:branch）
     * @param count 最多返回条数
     * @return 格式化的 commit 列表，或错误信息
     */
    public String getRecentCommits(String key, int count) {
        WatchEntry entry = watchList.get(key == null ? "" : key.trim().toLowerCase());
        if (entry == null) return "「" + key + "」不在监控列表中";
        if (entry.getSource() != WatchEntry.Source.GITHUB && entry.getSource() != WatchEntry.Source.GITEE) {
            return "「" + entry.getDisplayName() + "」不是 GitHub/Gitee 源，无法查看 commit 日志";
        }
        try {
            String json;
            if (entry.getSource() == WatchEntry.Source.GITEE) {
                String url = GITEE_API + "/repos/" + entry.getGithubRepo()
                        + "/commits?sha=" + entry.getBranch() + "&per_page=" + count
                        + (giteeToken.isEmpty() ? "" : "&access_token=" + giteeToken);
                json = fetchJsonGitee(url);
            } else {
                String url = GITHUB_API + "/repos/" + entry.getGithubRepo()
                        + "/commits?sha=" + entry.getBranch() + "&per_page=" + count;
                json = fetchJsonGitHub(url);
            }
            JsonArray commits = JsonParser.parseString(json).getAsJsonArray();
            if (commits.size() == 0) {
                return "分支 " + entry.getBranch() + " 没有 commit";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📜 ").append(entry.getDisplayName());
            sb.append("（").append(entry.getBranch()).append("）最近 ");
            sb.append(commits.size()).append(" 条提交：\n");
            sb.append("─────────────────\n");

            // 先收集原始 commit 信息，再批量翻译消息
            java.util.List<String> shaList = new java.util.ArrayList<>();
            java.util.List<String> dateList = new java.util.ArrayList<>();
            java.util.List<String> msgList = new java.util.ArrayList<>();
            for (int i = 0; i < commits.size(); i++) {
                JsonObject c = commits.get(i).getAsJsonObject();
                shaList.add(c.get("sha").getAsString());
                JsonObject commitObj = c.getAsJsonObject("commit");
                String date = "";
                if (commitObj.has("author")) {
                    JsonObject author = commitObj.getAsJsonObject("author");
                    if (author.has("date")) {
                        date = author.get("date").getAsString().substring(0, 10);
                    }
                }
                dateList.add(date);
                String message = commitObj.has("message")
                        ? commitObj.get("message").getAsString().split("\n")[0].trim() : "";
                msgList.add(message);
            }

            // 批量翻译 commit 消息（1 次 API 调用）
            java.util.List<String> translated = translator != null && translator.isEnabled()
                    ? translator.batchTranslateEnToZh(msgList) : msgList;

            for (int i = 0; i < shaList.size(); i++) {
                sb.append(dateList.get(i)).append(" ").append(shortSha(shaList.get(i)));
                sb.append(" ").append(translated.get(i));
                if (i < shaList.size() - 1) sb.append("\n");
            }
            sb.append("\n─────────────────\n");
            sb.append("🔗 https://github.com/").append(entry.getGithubRepo())
                    .append("/tree/").append(entry.getBranch());
            return sb.toString();
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    // ==================== 定时检查 ====================

    private void startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ModUpdateChecker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::doCheckAll,
                CHECK_DELAY_INITIAL, checkIntervalMinutes * 60, TimeUnit.SECONDS);
    }

    private void doCheckAll() {
        // per-mod 更新检查
        if (!watchList.isEmpty()) {
            info("开始检查模组更新（" + watchList.size() + " 个）...");

            int updated = 0;
            for (WatchEntry entry : watchList.values()) {
                try {
                    String oldId = entry.getLastVersionId();
                    String err;
                    if (entry.getSource() == WatchEntry.Source.GITHUB) {
                        err = fetchGitHubLatest(entry);
                    } else if (entry.getSource() == WatchEntry.Source.GITEE) {
                        err = fetchGiteeLatest(entry);
                    } else {
                        err = fetchModrinthLatest(entry);
                    }

                    if (err != null) {
                        warn("检查「" + entry.getSlug() + "」失败: " + err);
                    } else if (oldId != null && !oldId.equals(entry.getLastVersionId())) {
                        updated++;
                        notifyUpdate(entry, oldId);
                    }

                    // 分支监控：检查是否出现包含关键字的新分支
                    if (entry.hasBranchWatch()) {
                        checkBranchWatch(entry);
                    }

                    Thread.sleep(INTER_REQUEST_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    warn("检查「" + entry.getSlug() + "」异常: " + e.getMessage());
                }
            }
            info("模组更新检查完成，发现 " + updated + " 个更新");
            saveWatchState();
        }

        // Feed 新模组发现检查（按间隔控制频率）
        if (feedEnabled) {
            int tick = feedTickCounter.incrementAndGet();
            long feedEvery = Math.max(1, feedIntervalMinutes / Math.max(1, checkIntervalMinutes));
            if (tick >= feedEvery) {
                feedTickCounter.set(0);
                info("[Feed] 开始检查新模组...");
                int newCount = checkFeeds();
                if (newCount > 0) {
                    info("[Feed] 发现 " + newCount + " 个新模组");
                }
            }
        }
        // 注：GitHub / Gitee commit 动态追踪已迁移到 xt-github（/github 命令），此处不再重复检查。
    }

    private void notifyUpdate(WatchEntry entry, String oldId) {
        // 统一 markdown 卡片（与 mcmod / 新模组发现同一套审美）
        org.windy.xingtubot.common.util.Md card;
        if (entry.getSource() == WatchEntry.Source.GITHUB) {
            // 拉取 oldId..newId 之间的所有 commit 作为 changelog
            String changelog = fetchGitHubChangelog(entry.getGithubRepo(), oldId, entry.getLastVersionId());
            card = org.windy.xingtubot.common.util.Md.card("🆕", "模组更新提醒")
                    .subtitle("**" + entry.getDisplayName() + "** 有新的提交")
                    .field("🌿", "分支", entry.getBranch());
            appendChangelog(card, changelog);
            card.link("查看对比", "https://github.com/" + entry.getGithubRepo()
                    + "/compare/" + shortSha(oldId) + "..." + shortSha(entry.getLastVersionId()));
        } else if (entry.getSource() == WatchEntry.Source.GITEE) {
            String changelog = fetchGiteeChangelog(entry.getGithubRepo(), oldId, entry.getLastVersionId());
            card = org.windy.xingtubot.common.util.Md.card("🆕", "模组更新提醒")
                    .subtitle("**" + entry.getDisplayName() + "** 有新的提交")
                    .field("🌿", "分支", entry.getBranch());
            appendChangelog(card, changelog);
            card.link("查看提交", "https://gitee.com/" + entry.getGithubRepo()
                    + "/commits/" + entry.getBranch());
        } else {
            card = org.windy.xingtubot.common.util.Md.card("🆕", "模组更新提醒")
                    .subtitle("**" + entry.getDisplayName() + "** 已发布新版本")
                    .field("🎮", "MC版本", entry.getMcVersion() + " | " + capitalize(entry.getLoader()))
                    .link("查看版本", "https://modrinth.com/mod/" + entry.getSlug()
                            + "/version/" + entry.getLastVersionId());
        }
        String msg = card.build();

        boolean isGit = entry.getSource() == WatchEntry.Source.GITHUB
                || entry.getSource() == WatchEntry.Source.GITEE;
        String label = isGit
                ? shortSha(oldId) + " -> " + shortSha(entry.getLastVersionId())
                : oldId + " -> " + entry.getLastVersionId();
        info("发现更新: " + entry.getDisplayName() + " (" + label + ")");

        // 投递通知：sender 就绪走真·主动消息逐群推送，否则回退被动队列（实际路径见 notifyTargets 内日志）
        notifyTargets(msg, entry.getNotifyTargets());
    }

    /** 把 commit changelog（多行文本）逐行加进卡片；空则加一行占位。非空行前缀 {@code ·} 成清单。 */
    private void appendChangelog(org.windy.xingtubot.common.util.Md card, String changelog) {
        if (changelog == null || changelog.trim().isEmpty()) {
            card.line("　　（无提交说明）");
            return;
        }
        for (String raw : changelog.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            card.line("　· " + l);
        }
    }

    // ==================== Modrinth API ====================

    private String resolveProject(WatchEntry entry) {
        if (entry.getProjectId() != null && !entry.getProjectId().isEmpty()) return null;
        try {
            String json = fetchJson(MODRINTH_API + "/project/" + entry.getSlug());
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            entry.setProjectId(root.get("id").getAsString());
            entry.setDisplayName(root.has("title") ? root.get("title").getAsString() : entry.getSlug());
            return null;
        } catch (Exception e) {
            return "解析项目信息失败: " + e.getMessage();
        }
    }

    private String fetchModrinthLatest(WatchEntry entry) {
        try {
            String pid = entry.getProjectId();
            if (pid == null || pid.isEmpty()) {
                String err = resolveProject(entry);
                if (err != null) return err;
                pid = entry.getProjectId();
            }

            String url = MODRINTH_API + "/project/" + pid + "/version"
                    + "?game_versions=%5B%22" + entry.getMcVersion() + "%22%5D"
                    + "&loaders=%5B%22" + entry.getLoader() + "%22%5D";

            String json = fetchJson(url);
            JsonArray versions = JsonParser.parseString(json).getAsJsonArray();
            if (versions.size() == 0) {
                return "没有找到匹配 " + entry.getMcVersion() + "/" + entry.getLoader() + " 的版本";
            }

            JsonObject latest = versions.get(0).getAsJsonObject();
            String versionId = latest.get("id").getAsString();
            String versionNumber = latest.has("version_number")
                    ? latest.get("version_number").getAsString() : versionId;
            String name = latest.has("name") ? latest.get("name").getAsString() : versionNumber;

            entry.setLastVersionId(versionId);
            entry.setLastCheckTime(System.currentTimeMillis());
            if (entry.getDisplayName() == null) {
                entry.setDisplayName(name);
            }
            return null;
        } catch (Exception e) {
            return "查询 Modrinth 版本失败: " + e.getMessage();
        }
    }

    // ==================== GitHub API ====================

    /**
     * 获取 GitHub 两个 SHA 之间的 commit 列表（changelog）。
     * GET /repos/{owner}/{repo}/compare/{base}...{head}
     *
     * @return 格式化的 changelog 字符串
     */
    private String fetchGitHubChangelog(String ownerRepo, String oldSha, String newSha) {
        try {
            String url = GITHUB_API + "/repos/" + ownerRepo + "/compare/" + oldSha + "..." + newSha;
            String json = fetchJsonGitHub(url);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray commits = root.getAsJsonArray("commits");

            if (commits == null || commits.size() == 0) {
                return "Commit: " + shortSha(newSha);
            }

            int total = commits.size();
            // 最多显示 10 条，避免消息过长
            int show = Math.min(total, 10);

            // 先收集，再批量翻译
            java.util.List<String> shaList = new java.util.ArrayList<>();
            java.util.List<String> msgList = new java.util.ArrayList<>();
            for (int i = 0; i < show; i++) {
                JsonObject c = commits.get(i).getAsJsonObject();
                shaList.add(c.get("sha").getAsString());
                JsonObject commitObj = c.getAsJsonObject("commit");
                String message = commitObj.has("message")
                        ? commitObj.get("message").getAsString().split("\n")[0].trim() : "";
                msgList.add(message);
            }

            java.util.List<String> translated = translator != null && translator.isEnabled()
                    ? translator.batchTranslateEnToZh(msgList) : msgList;

            StringBuilder sb = new StringBuilder();
            sb.append("更新日志（").append(total).append(" 个 commit）：\n");
            for (int i = 0; i < shaList.size(); i++) {
                sb.append("· ").append(shortSha(shaList.get(i))).append(" ").append(translated.get(i));
                if (i < shaList.size() - 1) sb.append("\n");
            }
            if (total > show) {
                sb.append("\n· ... 还有 ").append(total - show).append(" 个 commit");
            }
            return sb.toString();
        } catch (Exception e) {
            // 拉 changelog 失败不影响主流程，降级为单行显示
            warn("[ModWatch/GitHub] 拉取 changelog 失败: " + e.getMessage());
            return "最新 Commit: " + shortSha(newSha);
        }
    }

    private String fetchGitHubLatest(WatchEntry entry) {
        try {
            String url = GITHUB_API + "/repos/" + entry.getGithubRepo()
                    + "/commits?sha=" + entry.getBranch() + "&per_page=1";

            String json = fetchJsonGitHub(url);
            JsonArray commits = JsonParser.parseString(json).getAsJsonArray();
            if (commits.size() == 0) {
                return "分支 " + entry.getBranch() + " 没有 commit";
            }

            JsonObject commit = commits.get(0).getAsJsonObject();
            String sha = commit.get("sha").getAsString();
            JsonObject commitObj = commit.getAsJsonObject("commit");
            String message = commitObj.has("message")
                    ? commitObj.get("message").getAsString().split("\n")[0].trim() : "";

            entry.setLastVersionId(sha);
            entry.setLastCommitMsg(message);
            entry.setLastCheckTime(System.currentTimeMillis());
            if (entry.getDisplayName() == null) {
                String repo = entry.getGithubRepo();
                entry.setDisplayName(repo.contains("/") ? repo.split("/")[1] : repo);
            }
            return null;
        } catch (Exception e) {
            return "查询 GitHub commit 失败: " + e.getMessage();
        }
    }

    private String fetchJsonGitHub(String urlPath) throws IOException {
        // 1. 直连 GitHub API（3 次重试）
        IOException lastExc = null;
        for (int i = 1; i <= 3; i++) {
            try {
                Http.Request req = Http.get(urlPath).proxy(proxy)
                        .userAgent("XingtuBot/1.0 (mod-update-checker)")
                        .header("Accept", "application/vnd.github+json");
                if (!githubToken.isEmpty()) {
                    req.header("Authorization", "Bearer " + githubToken);
                }
                Http.Response resp = req.send();
                if (resp.code == 403 && "0".equals(resp.header("X-RateLimit-Remaining"))) {
                    throw new IOException("GitHub API 限流（未认证 60次/小时），请配置 modwatch-github-token");
                }
                if (resp.code != 200) {
                    throw new IOException("HTTP " + resp.code);
                }
                return resp.body;
            } catch (IOException e) {
                lastExc = e;
                warn("[ModWatch/GitHub] 第 " + i + " 次直连失败: " + e.getMessage());
                try {
                    Thread.sleep(800L * i);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 2. 直连全部失败 → 走镜像源
        if (!githubMirrors.isEmpty()) {
            for (String mirror : githubMirrors) {
                try {
                    String mirrorUrl = mirror + urlPath;
                    info("[ModWatch/GitHub] 尝试镜像: " + mirror);
                    String result = fetchJsonMirror(mirrorUrl);
                    info("[ModWatch/GitHub] 镜像成功: " + mirror);
                    return result;
                } catch (IOException e) {
                    warn("[ModWatch/GitHub] 镜像失败 (" + mirror + "): " + e.getMessage());
                }
            }
        }

        throw lastExc;
    }

    /** 镜像源请求：带 GitHub Accept header，不带 Authorization（镜像站不需要 token）。 */
    private String fetchJsonMirror(String urlPath) throws IOException {
        for (int i = 1; i <= 2; i++) {
            try {
                Http.Response resp = Http.get(urlPath).proxy(proxy)
                        .userAgent("XingtuBot/1.0 (mod-update-checker)")
                        .header("Accept", "application/vnd.github+json")
                        .timeout(15000, 20000)
                        .send();
                if (resp.code != 200) {
                    throw new IOException("HTTP " + resp.code);
                }
                return resp.body;
            } catch (IOException e) {
                if (i == 2) throw e;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw new IOException("镜像请求失败");
    }

    // ==================== Gitee API ====================

    private String fetchGiteeLatest(WatchEntry entry) {
        try {
            String url = GITEE_API + "/repos/" + entry.getGithubRepo()
                    + "/commits?sha=" + entry.getBranch() + "&per_page=1"
                    + (giteeToken.isEmpty() ? "" : "&access_token=" + giteeToken);

            String json = fetchJsonGitee(url);
            JsonArray commits = JsonParser.parseString(json).getAsJsonArray();
            if (commits.size() == 0) {
                return "分支 " + entry.getBranch() + " 没有 commit";
            }

            JsonObject commit = commits.get(0).getAsJsonObject();
            String sha = commit.get("sha").getAsString();
            String message = commit.has("commit") ? commit.getAsJsonObject("commit").get("message").getAsString() : "";
            if (message.contains("\n")) message = message.split("\n")[0].trim();

            entry.setLastVersionId(sha);
            entry.setLastCommitMsg(message);
            entry.setLastCheckTime(System.currentTimeMillis());
            if (entry.getDisplayName() == null) {
                String repo = entry.getGithubRepo();
                entry.setDisplayName(repo.contains("/") ? repo.split("/")[1] : repo);
            }
            return null;
        } catch (Exception e) {
            return "查询 Gitee commit 失败: " + e.getMessage();
        }
    }

    /**
     * 获取 Gitee 两个 SHA 之间的 commit 列表。
     * Gitee 没有 compare API，退化为拉取分支最近 N 条 commit 并过滤。
     */
    private String fetchGiteeChangelog(String ownerRepo, String oldSha, String newSha) {
        try {
            // Gitee 无 compare 接口，拉最近 30 条，截取 oldSha..newSha 之间的
            String url = GITEE_API + "/repos/" + ownerRepo
                    + "/commits?sha=" + "&per_page=30"
                    + (giteeToken.isEmpty() ? "" : "&access_token=" + giteeToken);
            // 注意：不传 sha 参数则用默认分支，这里用 newSha 所在分支不可靠
            // 降级：只显示新 commit
            String simpleUrl = GITEE_API + "/repos/" + ownerRepo
                    + "/commits?per_page=10"
                    + (giteeToken.isEmpty() ? "" : "&access_token=" + giteeToken);
            String json = fetchJsonGitee(simpleUrl);
            JsonArray commits = JsonParser.parseString(json).getAsJsonArray();

            if (commits.size() == 0) {
                return "最新 Commit: " + shortSha(newSha);
            }

            // 收集直到 oldSha 为止
            java.util.List<String> shaList = new java.util.ArrayList<>();
            java.util.List<String> msgList = new java.util.ArrayList<>();
            for (int i = 0; i < commits.size(); i++) {
                JsonObject c = commits.get(i).getAsJsonObject();
                String sha = c.get("sha").getAsString();
                if (sha.equals(oldSha)) break;
                shaList.add(sha);
                String message = c.has("commit") ? c.getAsJsonObject("commit").get("message").getAsString() : "";
                if (message.contains("\n")) message = message.split("\n")[0].trim();
                msgList.add(message);
            }

            if (shaList.isEmpty()) {
                return "最新 Commit: " + shortSha(newSha);
            }

            java.util.List<String> translated = translator != null && translator.isEnabled()
                    ? translator.batchTranslateEnToZh(msgList) : msgList;

            int show = Math.min(shaList.size(), 10);
            StringBuilder sb = new StringBuilder();
            sb.append("更新日志（").append(shaList.size()).append(" 个 commit）：\n");
            for (int i = 0; i < show; i++) {
                sb.append("· ").append(shortSha(shaList.get(i))).append(" ").append(translated.get(i));
                if (i < show - 1) sb.append("\n");
            }
            if (shaList.size() > show) {
                sb.append("\n· ... 还有 ").append(shaList.size() - show).append(" 个 commit");
            }
            return sb.toString();
        } catch (Exception e) {
            warn("[ModWatch/Gitee] 拉取 changelog 失败: " + e.getMessage());
            return "最新 Commit: " + shortSha(newSha);
        }
    }

    /**
     * 查询 Gitee 仓库的分支列表，返回包含关键字的分支名（用于检测新版本分支）。
     *
     * @return 匹配的分支名列表，失败返回空列表
     */
    public java.util.List<String> findGiteeBranches(String ownerRepo, String keyword) {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            String url = GITEE_API + "/repos/" + ownerRepo + "/branches"
                    + (giteeToken.isEmpty() ? "" : "?access_token=" + giteeToken);
            String json = fetchJsonGitee(url);
            JsonArray branches = JsonParser.parseString(json).getAsJsonArray();
            for (int i = 0; i < branches.size(); i++) {
                String name = branches.get(i).getAsJsonObject().get("name").getAsString();
                if (name.contains(keyword)) {
                    result.add(name);
                }
            }
        } catch (Exception e) {
            warn("[ModWatch/Gitee] 查询分支失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 查询 GitHub 仓库的分支列表，返回包含关键字的分支名。
     *
     * @return 匹配的分支名列表，失败返回空列表
     */
    public java.util.List<String> findGitHubBranches(String ownerRepo, String keyword) {
        java.util.List<String> result = new java.util.ArrayList<>();
        try {
            // GitHub 分支列表 API，每页最多 100
            String url = GITHUB_API + "/repos/" + ownerRepo + "/branches?per_page=100";
            String json = fetchJsonGitHub(url);
            JsonArray branches = JsonParser.parseString(json).getAsJsonArray();
            for (int i = 0; i < branches.size(); i++) {
                String name = branches.get(i).getAsJsonObject().get("name").getAsString();
                if (name.contains(keyword)) {
                    result.add(name);
                }
            }
        } catch (Exception e) {
            warn("[ModWatch/GitHub] 查询分支失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 检查单个监控项的分支监控：遍历关键字，查匹配分支，通知新出现的。
     */
    private void checkBranchWatch(WatchEntry entry) {
        for (String keyword : entry.getBranchWatchKeywords()) {
            java.util.List<String> matched;
            if (entry.getSource() == WatchEntry.Source.GITEE) {
                matched = findGiteeBranches(entry.getGithubRepo(), keyword);
            } else if (entry.getSource() == WatchEntry.Source.GITHUB) {
                matched = findGitHubBranches(entry.getGithubRepo(), keyword);
            } else {
                continue;
            }

            for (String branch : matched) {
                if (!entry.getNotifiedBranches().contains(branch)) {
                    entry.getNotifiedBranches().add(branch);
                    // 排除当前正在监控的分支本身
                    if (branch.equalsIgnoreCase(entry.getBranch())) continue;
                    String platform = entry.getSource() == WatchEntry.Source.GITEE ? "Gitee" : "GitHub";
                    String url = entry.getSource() == WatchEntry.Source.GITEE
                            ? "https://gitee.com/" + entry.getGithubRepo() + "/tree/" + branch
                            : "https://github.com/" + entry.getGithubRepo() + "/tree/" + branch;
                    String msg = "🌿 新分支发现！\n"
                            + entry.getDisplayName() + " 出现了新分支: " + branch + "\n"
                            + "（匹配关键字: " + keyword + "）\n"
                            + "🔗 " + url;
                    info("发现新分支: " + entry.getDisplayName() + " → " + branch + "（关键字: " + keyword + "）");
                    notifyTargets(msg, entry.getNotifyTargets());
                }
            }

            try {
                Thread.sleep(INTER_REQUEST_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String fetchJsonGitee(String urlPath) throws IOException {
        IOException lastExc = null;
        for (int i = 1; i <= 3; i++) {
            try {
                Http.Response resp = Http.get(urlPath).proxy(proxy)
                        .userAgent("XingtuBot/1.0 (mod-update-checker)")
                        .header("Accept", "application/json")
                        .send();
                if (resp.code == 401) {
                    throw new IOException("Gitee 认证失败，请检查 modwatch-gitee-token");
                }
                if (resp.code == 403) {
                    throw new IOException("Gitee API 限流，请配置 modwatch-gitee-token");
                }
                if (resp.code != 200) {
                    throw new IOException("HTTP " + resp.code);
                }
                return resp.body;
            } catch (IOException e) {
                lastExc = e;
                warn("[ModWatch/Gitee] 第 " + i + " 次请求失败: " + e.getMessage());
                try {
                    Thread.sleep(800L * i);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw lastExc;
    }

    // ==================== HTTP（Modrinth） ====================

    private String fetchJson(String urlPath) throws IOException {
        IOException lastExc = null;
        for (int i = 1; i <= 3; i++) {
            try {
                Http.Response resp = Http.get(urlPath).proxy(proxy)
                        .userAgent("XingtuBot/1.0 (mod-update-checker)")
                        .header("Accept", "application/json")
                        .send();
                if (resp.code != 200) {
                    throw new IOException("HTTP " + resp.code);
                }
                return resp.body;
            } catch (IOException e) {
                lastExc = e;
                warn("[ModWatch] 第 " + i + " 次请求失败: " + e.getMessage());
                try {
                    Thread.sleep(800L * i);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw lastExc;
    }

    // ==================== 代理 ====================

    public static void setProxy(String host, int port, String type) {
        if (host == null || host.isEmpty() || type == null) {
            proxy = Proxy.NO_PROXY;
            return;
        }
        SocketAddress addr = new InetSocketAddress(host, port);
        switch (type.toLowerCase()) {
            case "socks":
                proxy = new Proxy(Proxy.Type.SOCKS, addr);
                break;
            case "http":
                proxy = new Proxy(Proxy.Type.HTTP, addr);
                break;
            default:
                proxy = Proxy.NO_PROXY;
                break;
        }
    }

    // ==================== 配置加载 ====================

    private void loadConfig() {
        checkIntervalMinutes = config.getInt("modwatch-interval-minutes", 60);
        defaultMcVersion = config.getString("modwatch-mc-version", "1.20.1");
        defaultLoader = config.getString("modwatch-loader", "forge");
        githubToken = config.getString("modwatch-github-token", "").trim();
        giteeToken = config.getString("modwatch-gitee-token", "").trim();
        githubMirrors.clear();
        List<String> mirrors = config.getStringList("modwatch-github-mirrors");
        if (mirrors != null) {
            for (String m : mirrors) {
                if (m != null && !m.trim().isEmpty()) {
                    // 确保尾部有 /
                    String trimmed = m.trim();
                    if (!trimmed.endsWith("/")) trimmed += "/";
                    githubMirrors.add(trimmed);
                }
            }
        }

        // Feed 模式配置
        feedEnabled = config.getBoolean("modwatch-feed-enable", false);
        feedIntervalMinutes = config.getInt("modwatch-feed-interval-minutes", 120);
        feedSources = config.getString("modwatch-feed-sources", "modrinth").trim().toLowerCase();
        feedModrinthEnabled = hasFeedSource("modrinth");
        feedMcmodEnabled = hasFeedSource("mcmod");
        feedCategories = config.getString("modwatch-feed-loader",
                config.getString("modwatch-feed-categories", "neoforge")).trim().toLowerCase();
        feedVersions = config.getString("modwatch-feed-version",
                config.getString("modwatch-feed-versions", "26.2")).trim();
        feedLimit = config.getInt("modwatch-feed-limit", 20);
        if (feedLimit < 1) feedLimit = 1;
        if (feedLimit > 100) feedLimit = 100;
        mcmodFeedMcver = config.getString("modwatch-feed-mcmod-mcver", feedVersions).trim();
        mcmodFeedPlatform = config.getInt("modwatch-feed-mcmod-platform", 1);
        mcmodFeedApi = config.getInt("modwatch-feed-mcmod-api", mcmodApiFromLoader(feedCategories));
        mcmodFeedLimit = config.getInt("modwatch-feed-mcmod-limit", feedLimit);
        if (mcmodFeedLimit < 1) mcmodFeedLimit = 1;
        if (mcmodFeedLimit > 100) mcmodFeedLimit = 100;

        // Feed 通知定向
        List<String> rawFeedNotify = config.getStringList("modwatch-feed-notify");
        if (rawFeedNotify != null && !rawFeedNotify.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String g : rawFeedNotify) {
                if (g != null && !g.trim().isEmpty()) filtered.add(g.trim());
            }
            feedNotifyTargets = filtered.isEmpty() ? Collections.emptyList() : filtered;
        } else {
            feedNotifyTargets = Collections.emptyList();
        }

        // 注：GitHub / Gitee 仓库追踪（含 modwatch-github-repos 等键）已迁移到 xt-github（/github 命令），
        // 此处不再读取，避免两套 github 追踪重复推送。

        // 通知定向配置（过滤空字符串）
        List<String> rawGroups = config.getStringList("notify-target-groups");
        if (rawGroups != null && !rawGroups.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String g : rawGroups) {
                if (g != null && !g.trim().isEmpty()) filtered.add(g.trim());
            }
            notifyTargetGroups = filtered.isEmpty() ? Collections.emptyList() : filtered;
        } else {
            notifyTargetGroups = Collections.emptyList();
        }
    }

    private void loadWatchListFromConfig() {
        // 优先尝试 map 格式（支持 per-item notify）
        List<Map<String, Object>> mapList = config.getStringMapList("modwatch-list");
        if (mapList != null && !mapList.isEmpty()) {
            for (Map<String, Object> entry : mapList) {
                Object slugVal = entry.get("slug");
                if (slugVal == null) continue;
                String slug = String.valueOf(slugVal).trim();
                if (slug.isEmpty()) continue;

                String[] parts = slug.split(":");
                String slugPart = parts[0].trim();
                String mcVer = parts.length > 1 ? parts[1].trim() : defaultMcVersion;
                String loader = parts.length > 2 ? parts[2].trim() : defaultLoader;

                String err = addWatch(slugPart, mcVer, loader);
                if (err != null) {
                    warn("配置预设监控「" + slug + "」加载失败: " + err);
                    continue;
                }

                // 解析 per-item notify
                Object notifyVal = entry.get("notify");
                if (notifyVal instanceof List) {
                    List<String> targets = new ArrayList<>();
                    for (Object n : (List<?>) notifyVal) {
                        String s = String.valueOf(n).trim();
                        if (!s.isEmpty()) targets.add(s);
                    }
                    if (!targets.isEmpty()) {
                        WatchEntry we = watchList.get(slugPart.toLowerCase());
                        if (we != null) we.setNotifyTargets(targets);
                    }
                }
            }
            return;
        }

        // 兼容旧的纯字符串列表格式
        List<String> list = config.getStringList("modwatch-list");
        if (list == null || list.isEmpty()) return;

        for (String item : list) {
            if (item == null || item.trim().isEmpty()) continue;
            String trimmed = item.trim();

            // 解析 |watch:keyword1,keyword2 后缀
            int pipeIdx = trimmed.indexOf('|');
            if (pipeIdx > 0) {
                trimmed = trimmed.substring(0, pipeIdx).trim();
            }

            if (trimmed.toLowerCase().startsWith("gh:") || trimmed.toLowerCase().startsWith("gitee:")) {
                warn("配置项「" + trimmed + "」已废弃，请迁移到 modwatch-github-repos 配置段");
                continue;
            }

            String[] parts = trimmed.split(":");
            String slug = parts[0].trim();
            String mcVer = parts.length > 1 ? parts[1].trim() : defaultMcVersion;
            String loader = parts.length > 2 ? parts[2].trim() : defaultLoader;
            if (slug.isEmpty()) continue;
            String err = addWatch(slug, mcVer, loader);
            if (err != null) {
                warn("配置预设监控「" + trimmed + "」加载失败: " + err);
            }
        }
    }

    // ==================== 翻译辅助 ====================

    /**
     * 翻译单条文本（如果翻译服务可用）。失败返回原文。
     */
    private String translate(String text) {
        if (translator == null || !translator.isEnabled()) return text;
        return translator.translateEnToZh(text);
    }

    // ==================== 工具 ====================

    private static String optStr(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
                ? obj.get(key).getAsString() : "";
    }

    private static String shortSha(String sha) {
        if (sha == null) return "?";
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private boolean hasFeedSource(String source) {
        if (feedSources == null || feedSources.trim().isEmpty()) return false;
        for (String raw : feedSources.split(",")) {
            String s = raw.trim().toLowerCase();
            if ("all".equals(s) || source.equals(s)) return true;
        }
        return false;
    }

    private int mcmodApiFromLoader(String loaderConfig) {
        String loader = loaderConfig == null ? "" : loaderConfig.trim().toLowerCase();
        int comma = loader.indexOf(',');
        if (comma >= 0) loader = loader.substring(0, comma).trim();
        switch (loader) {
            case "forge":
                return 1;
            case "fabric":
                return 2;
            case "quilt":
                return 11;
            case "neoforge":
            default:
                return 13;
        }
    }

    private String firstFeedLoader() {
        if (feedCategories == null || feedCategories.trim().isEmpty()) return "neoforge";
        String loader = feedCategories.trim();
        int comma = loader.indexOf(',');
        return comma >= 0 ? loader.substring(0, comma).trim() : loader;
    }

    private void info(String msg) {
        if (logger != null) logger.info("[ModWatch] " + msg);
    }

    private void warn(String msg) {
        if (logger != null) logger.warn("[ModWatch] " + msg);
    }
}
