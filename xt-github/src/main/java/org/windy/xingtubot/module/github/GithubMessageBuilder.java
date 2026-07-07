package org.windy.xingtubot.module.github;

import org.windy.xingtubot.common.service.Translator;
import org.windy.xingtubot.common.util.Md;

/**
 * 负责 GitHub 事件的文本处理、翻译和 Markdown 排版，将冗长的逻辑从 Module 中剥离。
 */
public class GithubMessageBuilder {

    private final Translator translator;

    public GithubMessageBuilder(Translator translator) {
        this.translator = translator;
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    private String translate(String text) {
        if (translator != null && translator.isEnabled()) {
            return translator.translateEnToZh(text);
        }
        return "";
    }

    public String buildRelease(String owner, String repo, String tagName, String name, String url) {
        String rawName = name == null || name.isEmpty() ? tagName : name;
        String translated = translate(rawName);

        Md card = Md.card("📦", "新版本发布")
                .subtitle("**" + owner + "/" + repo + "** ｜ `" + tagName + "`");

        if (!translated.equals(rawName) && !translated.isEmpty()) {
            card.quote("🌐 " + translated + "\n📝 原文: " + truncate(rawName, 100));
        } else {
            card.quote(truncate(rawName, 150));
        }

        return card.link("🔗 点击前往仓库查看下载", url).build();
    }

    public String buildCommit(String owner, String repo, String sha, String message, String author, String url) {
        String[] parts = message.split("\n", 2);
        String msgTitle = parts[0];
        String translatedTitle = translate(msgTitle);

        Md card = Md.card("🔨", "代码提交记录")
                .subtitle("**" + owner + "/" + repo + "**");

        if (!translatedTitle.equals(msgTitle) && !translatedTitle.isEmpty()) {
            card.quote("🌐 **" + translatedTitle + "**\n📝 " + truncate(msgTitle, 100));
        } else {
            card.quote("📝 " + truncate(msgTitle, 150));
        }

        card.field("👤", "提交者", author)
                .field("🔑", "Hash", "`" + sha + "`");

        return card.link("🔗 查看此次代码变更", url).build();
    }

    public String buildIssue(String owner, String repo, int number, String title, String action, String url) {
        String emoji = "open".equalsIgnoreCase(action) ? "🟢" : "🔴";
        String actionZh = "open".equalsIgnoreCase(action) ? "开启" : ("closed".equalsIgnoreCase(action) ? "关闭" : action);
        String translated = translate(title);

        Md card = Md.card(emoji, "Issue #" + number + " [" + actionZh + "]")
                .subtitle("**" + owner + "/" + repo + "**");

        if (!translated.equals(title) && !translated.isEmpty()) {
            card.quote("🌐 " + translated + "\n📝 原文: " + truncate(title, 100));
        } else {
            card.quote("📝 " + truncate(title, 150));
        }

        return card.link("🔗 参与讨论", url).build();
    }

    public String buildPr(String owner, String repo, int number, String title, String action, String url) {
        String emoji = "open".equalsIgnoreCase(action) ? "🟡" : "🟣";
        String actionZh = "open".equalsIgnoreCase(action) ? "发起" : ("closed".equalsIgnoreCase(action) ? "关闭/合并" : action);
        String translated = translate(title);

        Md card = Md.card(emoji, "Pull Request #" + number + " [" + actionZh + "]")
                .subtitle("**" + owner + "/" + repo + "**");

        if (!translated.equals(title) && !translated.isEmpty()) {
            card.quote("🌐 " + translated + "\n📝 原文: " + truncate(title, 100));
        } else {
            card.quote("📝 " + truncate(title, 150));
        }

        return card.link("🔗 审查代码请求", url).build();
    }
}