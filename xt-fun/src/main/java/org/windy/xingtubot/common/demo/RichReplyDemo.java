package org.windy.xingtubot.common.demo;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.event.BotMessageContext;

/**
 * 富消息 demo：群里发「测试」时，把 QQ 支持的各消息类型尽量各回一遍。
 *
 * <p>QQ 被动回复每条用户消息最多回 <b>5 次</b>，所以一次 demo 不能全发完。
 * 用配置 {@code demo-types} 选本次要发哪些（逗号分隔），默认 {@code text,image,markdown}。
 * 需要素材的类型未配置则跳过并在首条文本里说明。
 *
 * <p>可选类型：text / image / voice / video / markdown
 * （ark / embed 较冷门且需报备模板，已从 demo 移除，但 {@code QqOpenApiClient} 仍保留其方法备用）
 */
public final class RichReplyDemo {

    private RichReplyDemo() {
    }

    /** 若消息是触发词则执行 demo 并返回 true；否则返回 false。 */
    public static boolean maybeHandle(BotMessageContext event, BotConfig config) {
        String msg = event.getMessage() == null ? "" : event.getMessage().trim();
        if (!msg.equals("测试") && !msg.equalsIgnoreCase("/demo")) {
            return false;
        }
        run(event, config);
        return true;
    }

    public static void run(BotMessageContext event, BotConfig config) {
        // 图片地址：配了用配的；未配则跳过图片 demo
        String imageUrl = config.getString("demo-image-url", "");
        String voiceUrl = config.getString("demo-voice-url", "");
        String videoUrl = config.getString("demo-video-url", "");
        String mdKeyboardId = config.getString("demo-markdown-keyboard-id", "");
        String types = config.getString("demo-types", "text,image,markdown").toLowerCase();

        int budget = 5; // QQ 被动回复上限

        // 第 1 条永远是文本说明（不计入是否选了 text）
        StringBuilder sb = new StringBuilder("✅ 富消息 demo（被动回复每条限 5 次）\n");
        sb.append("本次将尝试: ").append(types).append("\n");
        sb.append("可选: text/image/voice/video/markdown，用 demo-types 配置\n");
        sb.append(skipNote("voice", types, voiceUrl, "demo-voice-url(silk)"));
        sb.append(skipNote("video", types, videoUrl, "demo-video-url(mp4)"));
        event.reply(sb.toString());
        budget--;

        if (want(types, "image") && !imageUrl.isEmpty() && budget > 0) {
            event.replyImage(imageUrl, "🖼 图片 demo");
            budget--;
        }
        if (want(types, "voice") && !voiceUrl.isEmpty() && budget > 0) {
            event.replyVoice(voiceUrl);
            budget--;
        }
        if (want(types, "video") && !videoUrl.isEmpty() && budget > 0) {
            event.replyVideo(videoUrl, "🎬 视频 demo");
            budget--;
        }
        if (want(types, "markdown") && budget > 0) {
            String md = "**Markdown demo**\n"
                    + "- 列表 A\n- 列表 B\n"
                    + "> 引用：XingtuBot\n\n"
                    + "**颜色测试**\n"
                    + "<font color=\"red\">红色</font> "
                    + "<font color=\"green\">绿色</font> "
                    + "<font color=\"#FF6600\">橙色(hex)</font> "
                    + "<font color=\"blue\">蓝色</font>\n"
                    + "普通文字对比";
            event.replyMarkdown(md, mdKeyboardId.isEmpty() ? null : mdKeyboardId);
            budget--;
        }
    }

    private static boolean want(String types, String name) {
        return types.contains(name);
    }

    /** 选了某类型但缺素材时，给出一行提示。 */
    private static String skipNote(String name, String types, String value, String configKey) {
        if (want(types, name) && (value == null || value.isEmpty())) {
            return "⚠️ " + name + " 跳过：未配置 " + configKey + "\n";
        }
        return "";
    }
}
