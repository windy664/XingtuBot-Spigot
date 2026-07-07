package org.windy.xingtubot.common.module.capability;

/**
 * 平台能力：主动向 QQ 群推送消息（不依赖被动回复窗口）。
 *
 * <p>底层 QQ apiClient 在 bot 连接成功后才就绪（晚于功能模块加载），故平台侧在
 * {@code loadAll} 前注册一个<b>惰性句柄</b>（{@link LazyProactiveSender}），bot ready 后再填实。
 * 功能模块（如 module-modtools 的模组更新通知）在加载期即可拿到句柄并持有，
 * 调用时若尚未就绪由实现决定回退（通常回退被动队列）。
 */
public interface ProactiveSender {

    /** 是否已就绪（底层 apiClient 已注入）。 */
    boolean isReady();

    /**
     * 主动推送一条群消息（纯文本）。
     *
     * @return 是否成功发出；false 表示未就绪或失败，调用方应回退被动队列
     */
    boolean sendGroupMessage(String groupOpenId, String message);

    /**
     * 主动推送一条群 Markdown 消息。机器人无原生 markdown 权限时由实现回退为纯文本。
     * 默认实现直接走 {@link #sendGroupMessage}，供尚未支持 markdown 的实现兼容。
     *
     * @return 是否成功发出；false 表示未就绪或失败，调用方应回退被动队列
     */
    default boolean sendGroupMarkdown(String groupOpenId, String markdownContent) {
        return sendGroupMessage(groupOpenId, markdownContent);
    }

    /**
     * 主动推送一条群 Markdown 消息，并附带<b>自定义内联按钮键盘</b>
     * （{@code keyboardJson} 为 {@code Keyboards.callback(...)} 产出的 JSON 字符串）。
     * 默认实现忽略键盘、退化为普通 markdown，供尚不支持键盘的实现兼容。
     *
     * @return 是否成功发出；false 表示未就绪或失败
     */
    default boolean sendGroupMarkdownKeyboard(String groupOpenId, String markdownContent, String keyboardJson) {
        return sendGroupMarkdown(groupOpenId, markdownContent);
    }
}
