package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.platform.BotLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 表情包管理器：从本地目录加载表情包图片，根据语境随机选取。
 *
 * <p>目录结构（在插件 dataFolder 下）：
 * <pre>
 * stickers/
 *   happy/      ← 开心/哈哈/笑
 *   angry/      ← 生气/怒
 *   sad/        ← 难过/哭
 *   agree/      ← 同意/对/没错
 *   disagree/   ← 不同意/反对/才不是
 *   shock/      ← 震惊/惊了/卧槽
 *   default/    ← 兜底表情
 * </pre>
 *
 * <p>LLM 回复时如果包含 [sticker:xxx] 标记，从对应目录随机取一张图发出去。
 * 文字部分照常发，表情包跟在后面。
 */
public final class StickerManager {

    private final Map<String, List<String>> packs = new HashMap<>();
    private final File stickerDir;
    private final BotLogger logger;
    private volatile boolean loaded = false;

    public StickerManager(File dataFolder, BotLogger logger) {
        this.stickerDir = new File(dataFolder, "stickers");
        this.logger = logger;
    }

    /** 加载表情包目录（启动时调一次） */
    public void load() {
        if (!stickerDir.exists()) {
            stickerDir.mkdirs();
            // 创建示例目录
            for (String pack : Arrays.asList("happy", "angry", "sad", "agree", "disagree", "shock", "default")) {
                new File(stickerDir, pack).mkdirs();
            }
            if (logger != null) {
                logger.info("[AI-Sticker] 已创建表情包目录: " + stickerDir.getAbsolutePath());
                logger.info("[AI-Sticker] 请在各子目录放入图片文件（jpg/png/gif）");
            }
            loaded = true;
            return;
        }

        File[] dirs = stickerDir.listFiles(File::isDirectory);
        if (dirs == null) return;

        for (File dir : dirs) {
            String packName = dir.getName().toLowerCase();
            File[] images = dir.listFiles((d, name) ->
                    name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".jpeg"));
            if (images != null && images.length > 0) {
                List<String> paths = new ArrayList<>();
                for (File img : images) {
                    paths.add(img.getAbsolutePath());
                }
                packs.put(packName, paths);
            }
        }

        loaded = true;
        if (logger != null) {
            logger.info("[AI-Sticker] 已加载 " + packs.size() + " 个表情包分组");
        }
    }

    /**
     * 从 LLM 回复中提取表情包标记，返回表情包路径（如果有的话）。
     * LLM 回复格式：文字内容 [sticker:happy]
     * @return 表情包文件路径，没有则返回 null
     */
    public String extractSticker(String reply) {
        if (reply == null || !loaded) return null;

        // 匹配 [sticker:xxx] 或 [表情:xxx]
        int start = reply.indexOf("[sticker:");
        if (start < 0) start = reply.indexOf("[表情:");
        if (start < 0) return null;

        int end = reply.indexOf("]", start);
        if (end < 0) return null;

        String tag = reply.substring(start + (reply.charAt(start + 1) == 's' ? 9 : 4), end).trim().toLowerCase();
        return getRandomSticker(tag);
    }

    /**
     * 从 LLM 回复中移除表情包标记，返回纯文字。
     */
    public String stripStickerTag(String reply) {
        if (reply == null) return reply;
        return reply.replaceAll("\\[sticker:[^\\]]*\\]", "")
                    .replaceAll("\\[表情:[^\\]]*\\]", "")
                    .trim();
    }

    /**
     * 根据情绪标签随机获取一张表情包路径。
     * @param tag 情绪标签（happy/angry/sad/agree/disagree/shock/default）
     */
    public String getRandomSticker(String tag) {
        if (tag == null || tag.isEmpty()) tag = "default";

        List<String> list = packs.get(tag);
        if (list == null || list.isEmpty()) {
            // 回退到 default
            list = packs.get("default");
        }
        if (list == null || list.isEmpty()) return null;

        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /** 是否已加载且有可用表情包 */
    public boolean hasStickers() {
        return loaded && !packs.isEmpty();
    }

    /** 获取所有可用的表情包分组名（供系统提示告诉LLM） */
    public String getAvailablePacks() {
        if (packs.isEmpty()) return "无";
        return String.join("/", packs.keySet());
    }
}
