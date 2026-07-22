package org.windy.xingtubot.ext.xtfun.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.util.Http;

import java.util.List;

/**
 * 随机二次元图片：「来张图」「随机图片」「来点图」「二次元」。
 */
public class AnimePicCommand implements BotCommand {

    private static final String[] DEFAULT_APIS = {
            "https://www.dmoe.cc/random.php",
            "https://api.mtyqx.cn/api/random.php"
    };

    private final String[] sources;

    public AnimePicCommand() {
        this.sources = DEFAULT_APIS;
    }

    public AnimePicCommand(List<String> sources) {
        this.sources = (sources != null && !sources.isEmpty())
                ? sources.toArray(new String[0])
                : DEFAULT_APIS;
    }

    @Override
    public boolean matches(String message) {
        return message.equals("来张图") || message.equals("随机图片")
                || message.equals("来点图") || message.equals("二次元");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        for (String api : sources) {
            try {
                String imageUrl = Http.head(api).userAgent("XingtuBot").timeout(8000, 8000)
                        .followRedirects(false).resolveRedirect();
                event.replyImage(imageUrl, "🖼 来啦~");
                return;
            } catch (Exception ignored) {
            }
        }
        event.reply("图片服务暂时不可用，稍后再试~");
    }

    @Override
    public String name() { return "animepic"; }
    @Override
    public String usage() { return "来张图 / 随机图片"; }
    @Override
    public String description() { return "随机二次元图片"; }
    @Override
    // category 自动继承模块 displayName
}
