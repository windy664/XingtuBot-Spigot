package org.windy.xingtubot.ext.xtfun.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.image.TextImageRenderer;

/**
 * 文字生图命令：生图 <模板> <文字>。
 */
public class TextImageCommand implements BotCommand {

    private final TextImageRenderer renderer;

    public TextImageCommand(TextImageRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public boolean matches(String message) {
        return message.trim().startsWith("生图");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String rest = message.trim().substring(2).trim();
        if (rest.isEmpty()) {
            event.reply("用法：生图 <模板> <文字>，例如：生图 公告 今晚8点联机");
            return;
        }
        int sp = rest.indexOf(' ');
        final String template = sp > 0 ? rest.substring(0, sp) : "default";
        final String text = sp > 0 ? rest.substring(sp + 1).trim() : rest;
        try {
            byte[] png = renderer.render(template, text);
            event.replyImageData(png, "");
        } catch (Exception e) {
            event.reply("生图失败: " + e.getMessage());
        }
    }

    @Override
    public String name() { return "text-image"; }
    @Override
    public String usage() { return "生图 <模板> <文字>"; }
    @Override
    public String description() { return "文字转图片"; }
    @Override
    public String category() { return "🎮 娱乐"; }
}
