package org.windy.xingtubot.common.event;

/**
 * 回复器：抽象“怎么把回复发回去”。
 *
 * <p>注意：键盘/Ark/Embed 一律用 <b>JSON 字符串</b>传递，<b>不</b>用 gson 类型——
 * 因为 bundle 会 relocate gson(org.lib.gson)，而扩展插件编译用的是 com.google.gson，
 * gson 类型若出现在跨插件 API 签名里会运行时失配(NoSuchMethodError)。各实现内部自行 parse。
 *
 * <p>纯文本通道（如老的昕途 WSS）只需实现 {@link #replyText}，富消息方法会自动降级为发文本，
 * 因此老通道零改动、不受影响；Webhook 通道则实现全部方法、走 OpenAPI 发送富媒体/Markdown/Ark。
 */
public interface BotReplier {

    /** 发送文本（唯一必须实现的方法）。 */
    void replyText(String text);

    /** 发送图片；不支持富媒体的通道降级为发 content 文本。 */
    default void replyImage(String imageUrl, String content) {
        replyText(content != null ? content : "");
    }

    /** 发送图片（base64 字节直传，不依赖公网/SCF）；不支持的通道降级为发 content。 */
    default void replyImageData(byte[] imageBytes, String content) {
        replyText(content != null ? content : "");
    }

    /** 发送语音（silk 格式 url）；不支持的通道忽略。 */
    default void replyVoice(String voiceUrl) {
        // 文本通道不支持，忽略
    }

    /** 发送语音（音频字节直传，silk/mp3 等，不依赖公网）；不支持的通道忽略。 */
    default void replyVoiceData(byte[] audioBytes) {
        // 文本通道不支持，忽略
    }

    /** 发送视频（mp4 url）；不支持的通道降级为发 content。 */
    default void replyVideo(String videoUrl, String content) {
        replyText(content != null ? content : "");
    }

    /** 发送 Embed（实验性，主要面向频道）；入参为 embed 的 JSON 字符串；不支持的通道忽略。 */
    default void replyEmbed(String embedJson) {
        // 文本通道不支持，忽略
    }

    /** 发送 Markdown（可带键盘模板）；不支持的通道降级为发 content。 */
    default void replyMarkdown(String content, String keyboardTemplateId) {
        replyText(content);
    }

    /** 发送 Markdown + 内联按钮键盘；keyboardJson 为键盘对象的 JSON 字符串；不支持的通道降级为发 content。 */
    default void replyKeyboard(String markdownContent, String keyboardJson) {
        replyText(markdownContent);
    }

    /** 发送 Ark 卡片；入参为 ark 的 JSON 字符串；不支持的通道默认忽略。 */
    default void replyArk(String arkJson) {
        // 文本通道不支持，忽略
    }
}
