package org.windy.xingtubot.common.poll;

import org.windy.xingtubot.common.api.QqOpenApiClient;
import org.windy.xingtubot.common.event.BotReplier;
import org.windy.xingtubot.common.platform.PlatformAdapter;

/**
 * 主动消息回复器：用于<b>没有 msg_id / event_id 可挂靠</b>的场景——典型是按钮交互
 * （{@code INTERACTION_CREATE}）回应后给群/单聊发回复。
 *
 * <p>走 OpenAPI 主动消息接口（需机器人具备主动消息权限）。富媒体（图片/语音/视频/ark）
 * 主动通道这里不展开，按 {@link BotReplier} 默认降级为文本。
 */
public class ProactiveReplier implements BotReplier {

    private final QqOpenApiClient api;
    private final PlatformAdapter adapter;
    private final String groupOpenid;
    private final String userOpenid;
    private final boolean isGroup;

    public ProactiveReplier(QqOpenApiClient api, PlatformAdapter adapter,
                            String groupOpenid, String userOpenid, boolean isGroup) {
        this.api = api;
        this.adapter = adapter;
        this.groupOpenid = groupOpenid;
        this.userOpenid = userOpenid;
        this.isGroup = isGroup;
    }

    @Override
    public void replyText(String text) {
        adapter.runAsync(() -> {
            try {
                if (isGroup) api.sendProactiveGroupMessage(groupOpenid, text);
                else api.sendProactiveC2CMessage(userOpenid, text);
            } catch (Exception e) {
                adapter.log("[交互] 主动回复失败: " + e.getMessage());
            }
        });
    }

    @Override
    public void replyMarkdown(String content, String keyboardTemplateId) {
        adapter.runAsync(() -> {
            try {
                if (isGroup) api.sendProactiveGroupMarkdown(groupOpenid, content);
                else api.sendProactiveC2CMessage(userOpenid, content); // 单聊主动 markdown 未单列，降级文本
            } catch (Exception e) {
                adapter.log("[交互] 主动回复失败: " + e.getMessage());
            }
        });
    }

    @Override
    public void replyKeyboard(String markdownContent, String keyboardJson) {
        // 主动通道的内联键盘这里不展开，先按 markdown 文本回（保证按钮回调链路可用）
        replyMarkdown(markdownContent, null);
    }
}
