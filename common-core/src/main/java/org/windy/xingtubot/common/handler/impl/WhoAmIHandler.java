package org.windy.xingtubot.common.handler.impl;

import org.windy.xingtubot.common.event.BotMessageEvent;
import org.windy.xingtubot.common.handler.MessageHandler;

/**
 * 内置核心诊断命令：返回当前用户 ID（openid）与当前群 ID（group_openid）。
 *
 * <p>用途：配置时需要把管理员 openid 填到 {@code admin-openids}、把目标群 group_openid 填到
 * {@code allowed-groups} / 各类 notify 列表，但这些 ID 是 QQ 官方下发的不透明串，肉眼拿不到。
 * 在群里发「id」即可让机器人回显，方便复制粘贴进配置。
 *
 * <p>核心内置（不属于任何附属扩展），由各平台命令处理器直接注册到注册中心。
 */
public class WhoAmIHandler implements MessageHandler {

    @Override
    public boolean matches(String message, BotMessageEvent event) {
        String m = message == null ? "" : message.trim().toLowerCase();
        return m.equals("id") || m.equals("/id")
                || m.equals("我的id") || m.equals("myid")
                || m.equals("群id") || m.equals("groupid");
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        String userId = event.getFormId();
        String groupId = event.getGuildId();

        StringBuilder sb = new StringBuilder("## 🪪 ID 信息\n");
        sb.append("**你的用户 ID（openid）**\n");
        sb.append("`").append(userId == null || userId.isEmpty() ? "(未知)" : userId).append("`\n");

        // 群上下文判定不依赖 eventType（可能被装饰链丢失）：群消息里 guildId=群openid≠发送者openid；
        // 私聊 C2C 里 guildId 回退成发送者 openid（与 userId 相同）→ 视为无群。
        boolean inGroup = groupId != null && !groupId.isEmpty() && !groupId.equals(userId);
        if (inGroup) {
            sb.append("\n**当前群 ID（group_openid）**\n");
            sb.append("`").append(groupId).append("`\n");
        }

        sb.append("\n> 💡 管理员把用户 ID 填进 `admin-openids`；");
        sb.append("把群 ID 填进 `allowed-groups` / 推送目标群即可。");
        if (!inGroup) {
            sb.append("\n> ℹ️ 当前是私聊，没有群 ID。要拿群 ID 请到目标群里 @我 发送 `id`。");
        }

        event.replyMarkdown(sb.toString(), null);
    }

    @Override
    public String name() {
        return "whoami";
    }

    @Override
    public int priority() {
        // 较高优先级（小值）：纯关键词命令，先于自定义问答/群服互联兜底匹配
        return 15;
    }

    @Override
    public String usage() {
        return "id";
    }

    @Override
    public String description() {
        return "查看你的用户 ID / 当前群 ID（配置用）";
    }

    @Override
    public String category() {
        return "⚙ 系统";
    }
}
