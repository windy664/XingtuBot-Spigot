package org.windy.xingtubot.common.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模组监控项：记录一个被监控模组的状态。
 * 支持三种数据源：Modrinth（已发布版本）、GitHub（分支 commit）、Gitee（分支 commit）。
 */
public class WatchEntry {

    /** 数据源类型 */
    public enum Source { MODRINTH, GITHUB, GITEE }

    private Source source = Source.MODRINTH;
    private String slug;           // Modrinth slug，或 GitHub/Gitee 场景下的 key
    private String projectId;      // Modrinth project ID（仅 Modrinth，首次查询时缓存）
    private String mcVersion;      // 目标 MC 版本（仅 Modrinth）
    private String loader;         // 加载器（仅 Modrinth）
    private String lastVersionId;  // 上次检测到的标识：Modrinth = version ID，Git = commit SHA
    private String displayName;    // 模组显示名
    private long lastCheckTime;    // 上次检查时间戳

    // Git 专属字段（GitHub / Gitee 共用）
    private String githubRepo;     // owner/repo
    private String branch;         // 分支名
    private String lastCommitMsg;  // 上次 commit message 的第一行

    // 分支监控：定时检查仓库是否出现包含关键字的新分支
    private Set<String> branchWatchKeywords;   // 要监控的关键字（如 "26.2"）
    private Set<String> notifiedBranches;      // 已通知过的分支名（防重复通知）

    // per-item 通知定向（null/空=走全局，含"*"=推全部群）
    private java.util.List<String> notifyTargets;

    public WatchEntry() {
    }

    /** Modrinth 便捷构造 */
    public static WatchEntry ofModrinth(String slug, String mcVersion, String loader) {
        WatchEntry e = new WatchEntry();
        e.source = Source.MODRINTH;
        e.slug = slug;
        e.mcVersion = mcVersion;
        e.loader = loader;
        return e;
    }

    /** GitHub 便捷构造 */
    public static WatchEntry ofGitHub(String ownerRepo, String branch) {
        WatchEntry e = new WatchEntry();
        e.source = Source.GITHUB;
        e.githubRepo = ownerRepo;
        e.branch = branch != null ? branch : "main";
        e.slug = "gh:" + ownerRepo + ":" + e.branch;
        return e;
    }

    /** Gitee 便捷构造（复用 githubRepo 存 owner/repo） */
    public static WatchEntry ofGitee(String ownerRepo, String branch) {
        WatchEntry e = new WatchEntry();
        e.source = Source.GITEE;
        e.githubRepo = ownerRepo;
        e.branch = branch != null ? branch : "master";
        e.slug = "gitee:" + ownerRepo + ":" + e.branch;
        return e;
    }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String mcVersion) { this.mcVersion = mcVersion; }
    public String getLoader() { return loader; }
    public void setLoader(String loader) { this.loader = loader; }
    public String getLastVersionId() { return lastVersionId; }
    public void setLastVersionId(String lastVersionId) { this.lastVersionId = lastVersionId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public long getLastCheckTime() { return lastCheckTime; }
    public void setLastCheckTime(long lastCheckTime) { this.lastCheckTime = lastCheckTime; }
    public String getGithubRepo() { return githubRepo; }
    public void setGithubRepo(String githubRepo) { this.githubRepo = githubRepo; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getLastCommitMsg() { return lastCommitMsg; }
    public void setLastCommitMsg(String lastCommitMsg) { this.lastCommitMsg = lastCommitMsg; }

    public Set<String> getBranchWatchKeywords() {
        return branchWatchKeywords != null ? branchWatchKeywords : Collections.emptySet();
    }
    public void setBranchWatchKeywords(Set<String> keywords) { this.branchWatchKeywords = keywords; }
    public void addBranchWatchKeyword(String keyword) {
        if (branchWatchKeywords == null) branchWatchKeywords = new HashSet<>();
        branchWatchKeywords.add(keyword);
    }

    public Set<String> getNotifiedBranches() {
        if (notifiedBranches == null) notifiedBranches = new HashSet<>();
        return notifiedBranches;
    }

    /** 是否有分支监控关键字 */
    public boolean hasBranchWatch() {
        return branchWatchKeywords != null && !branchWatchKeywords.isEmpty();
    }

    public java.util.List<String> getNotifyTargets() {
        return notifyTargets;
    }
    public void setNotifyTargets(java.util.List<String> targets) {
        this.notifyTargets = targets;
    }

    /** 序列化为 JSON（仅持久化运行时状态，不含配置项） */
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("slug", slug);
        obj.addProperty("source", source.name());
        if (lastVersionId != null) obj.addProperty("lastVersionId", lastVersionId);
        if (displayName != null) obj.addProperty("displayName", displayName);
        if (lastCommitMsg != null) obj.addProperty("lastCommitMsg", lastCommitMsg);
        if (projectId != null) obj.addProperty("projectId", projectId);
        obj.addProperty("lastCheckTime", lastCheckTime);
        if (notifiedBranches != null && !notifiedBranches.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String b : notifiedBranches) arr.add(b);
            obj.add("notifiedBranches", arr);
        }
        if (notifyTargets != null && !notifyTargets.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String t : notifyTargets) arr.add(t);
            obj.add("notifyTargets", arr);
        }
        return obj;
    }

    /** 从 JSON 恢复运行时状态（source/slug 必须已有，由配置加载时设置） */
    public void loadFromJson(JsonObject obj) {
        if (obj.has("lastVersionId")) lastVersionId = obj.get("lastVersionId").getAsString();
        if (obj.has("displayName")) displayName = obj.get("displayName").getAsString();
        if (obj.has("lastCommitMsg")) lastCommitMsg = obj.get("lastCommitMsg").getAsString();
        if (obj.has("projectId")) projectId = obj.get("projectId").getAsString();
        if (obj.has("lastCheckTime")) lastCheckTime = obj.get("lastCheckTime").getAsLong();
        if (obj.has("notifiedBranches")) {
            JsonArray arr = obj.getAsJsonArray("notifiedBranches");
            notifiedBranches = new HashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                notifiedBranches.add(arr.get(i).getAsString());
            }
        }
        if (obj.has("notifyTargets")) {
            JsonArray arr = obj.getAsJsonArray("notifyTargets");
            notifyTargets = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                notifyTargets.add(arr.get(i).getAsString());
            }
        }
    }

    @Override
    public String toString() {
        return displayName != null ? displayName : slug;
    }
}
