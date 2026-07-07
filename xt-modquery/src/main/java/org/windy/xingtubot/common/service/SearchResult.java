package org.windy.xingtubot.common.service;

import java.util.List;

/**
 * Modrinth/CurseForge 搜索结果条目。
 */
public class SearchResult {
    public final String slug;
    public final String title;
    public final String description;
    public final String author;
    public final String iconUrl;
    public final int downloads;
    public final String projectType;
    public final List<String> categories;

    public SearchResult(String slug, String title, String description, String author,
                        String iconUrl, int downloads, String projectType, List<String> categories) {
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.author = author;
        this.iconUrl = iconUrl;
        this.downloads = downloads;
        this.projectType = projectType;
        this.categories = categories;
    }
}
