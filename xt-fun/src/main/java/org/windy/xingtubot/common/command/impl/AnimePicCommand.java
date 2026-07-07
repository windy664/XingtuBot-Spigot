package org.windy.xingtubot.common.command.impl;

import org.windy.xingtubot.common.command.GroupCommand;
import org.windy.xingtubot.common.command.HttpUtil;
import org.windy.xingtubot.common.event.BotMessageEvent;

/**
 * 随机二次元图片：「来张图」「随机图片」「来点图」「二次元」。
 *
 * <p>直接使用第三方图源 URL。如果 QQ 服务器无法下载（防盗链/不可达），
 * 图片发送会失败并提示用户稍后重试。
 */
public class AnimePicCommand implements GroupCommand {

    // 随机二次元图源（须返回 jpg/png，QQ 不支持 webp）。dmoe 返回 jpeg。
    private static final String[] APIS = {
            "https://www.dmoe.cc/random.php",
            "https://api.mtyqx.cn/api/random.php"
    };

    public AnimePicCommand() {
    }

    @Override
    public boolean matches(String message) {
        return message.equals("来张图") || message.equals("随机图片")
                || message.equals("来点图") || message.equals("二次元");
    }

    @Override
    public void handle(String message, BotMessageEvent event) {
        for (String api : APIS) {
            try {
                String imageUrl = HttpUtil.resolveRedirect(api);
                event.replyImage(imageUrl, "🖼 来啦~");
                return;
            } catch (Exception ignored) {
                // 换下一个源
            }
        }
        event.reply("图片服务暂时不可用，稍后再试~");
    }

    @Override
    public String name() {
        return "animepic";
    }
    @Override
    public String usage() { return "来张图 / 随机图片"; }
    @Override
    public String description() { return "随机二次元图片"; }
    @Override
    public String category() { return "🎮 娱乐"; }}
