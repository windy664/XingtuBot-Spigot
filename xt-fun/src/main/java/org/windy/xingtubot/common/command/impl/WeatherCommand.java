package org.windy.xingtubot.common.command.impl;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.util.Http;
import org.windy.xingtubot.common.util.Md;

/**
 * 天气查询：「天气 北京」。用 wttr.in（免密钥、中文、国内可达性一般，失败给提示）。
 */
public class WeatherCommand implements BotCommand {

    @Override
    public boolean matches(String message) {
        return message.startsWith("天气");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        String city = message.substring("天气".length()).trim();
        if (city.isEmpty()) {
            event.reply("用法：天气 城市名，例如「天气 北京」");
            return;
        }
        try {
            // 各字段分开取，便于排成 Markdown 卡片
            String fmt = Http.enc("%C|%t|%f|%h|%w|%p");
            String url = "https://wttr.in/" + Http.enc(city) + "?format=" + fmt + "&lang=zh&m";
            Http.Response response = Http.get(url).userAgent("XingtuBot").timeout(8000, 10000).send();
            if (response.code >= 400) throw new RuntimeException("HTTP " + response.code);
            String raw = response.body.trim();
            if (raw.isEmpty() || raw.toLowerCase().contains("unknown") || !raw.contains("|")) {
                event.reply("没查到「" + city + "」的天气，换个城市名试试~");
                return;
            }
            String[] p = raw.split("\\|");
            String temp = get(p, 1);
            String feel = get(p, 2);
            String md = Md.card("🌤", city + " · 天气")
                    .field("☁️", "天气", get(p, 0))
                    .field("🌡️", "温度", "—".equals(feel) ? temp : temp + "（体感 " + feel + "）")
                    .field("💧", "湿度", get(p, 3))
                    .field("🌬️", "风力", get(p, 4))
                    .field("☔", "降水", get(p, 5))
                    .build();
            event.replyMarkdown(md, null);
        } catch (Exception e) {
            event.reply("天气服务暂时不可用，稍后再试~");
        }
    }

    private String get(String[] arr, int i) {
        return (i < arr.length && !arr[i].trim().isEmpty()) ? arr[i].trim() : "—";
    }

    @Override
    public String name() {
        return "weather";
    }
    @Override
    public String usage() { return "天气 <城市>"; }
    @Override
    public String description() { return "查询实时天气"; }
    @Override
    public String category() { return "🎮 娱乐"; }}
