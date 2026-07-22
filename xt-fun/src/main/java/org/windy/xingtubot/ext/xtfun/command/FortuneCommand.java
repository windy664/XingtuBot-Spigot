package org.windy.xingtubot.ext.xtfun.command;

import org.windy.xingtubot.common.command.BotCommand;
import org.windy.xingtubot.common.event.BotMessageContext;
import org.windy.xingtubot.common.util.Md;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 趣味随机：运势 / 骰子 / 选择。纯本地，零依赖零成本。
 */
public class FortuneCommand implements BotCommand {

    private static final String[] LEVELS = {
            "大吉", "中吉", "小吉", "吉", "末吉", "凶", "大凶"
    };
    private static final String[] TIPS = {
            "宜冒险，挖矿有惊喜", "宜肝服务器，今日效率拉满", "宜社交，群里多冒泡",
            "忌单挑Boss，容易翻车", "忌熬夜，早点下线", "宜囤资源，别浪费",
            "今日适合开新档", "小心脚下的岩浆", "适合和队友联机"
    };

    @Override
    public boolean matches(String message) {
        return message.equals("运势") || message.equals("骰子") || message.equalsIgnoreCase("roll")
                || message.startsWith("选择");
    }

    @Override
    public void handle(String message, BotMessageContext event) {
        if (message.equals("运势")) {
            event.replyMarkdown(fortuneCard(event.getSenderId()), null);
        } else if (message.equals("骰子") || message.equalsIgnoreCase("roll")) {
            event.reply("🎲 你掷出了 " + (ThreadLocalRandom.current().nextInt(100) + 1) + " 点");
        } else if (message.startsWith("选择")) {
            event.reply(choose(message.substring(2).trim()));
        }
    }

    private String fortuneCard(String openid) {
        long seed = stableSeed((openid == null ? "" : openid) + LocalDate.now());
        java.util.Random r = new java.util.Random(seed);
        String level = LEVELS[r.nextInt(LEVELS.length)];
        String tip = TIPS[r.nextInt(TIPS.length)];
        int luck = r.nextInt(101);
        int bars = luck / 10;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) bar.append(i < bars ? "█" : "░");
        return Md.card("🔮", "今日运势")
                .field("🎴", "签", level)
                .field("🍀", "幸运值", luck + "/100")
                .line("`" + bar + "`")
                .quote(tip)
                .build();
    }

    private String choose(String raw) {
        String[] parts = raw.split("还是|or|,|，|/| ");
        java.util.List<String> opts = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) opts.add(t);
        }
        if (opts.size() < 2) return "用法：选择 A 还是 B";
        return "🤔 我选：" + opts.get(ThreadLocalRandom.current().nextInt(opts.size()));
    }

    private long stableSeed(String s) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            long seed = 0;
            for (int i = 0; i < 8; i++) seed = (seed << 8) | (h[i] & 0xFF);
            return seed;
        } catch (Exception e) {
            return s.hashCode();
        }
    }

    @Override
    public String name() { return "fortune"; }
    @Override
    public String usage() { return "运势 / 骰子 / 选择 A 还是 B"; }
    @Override
    public String description() { return "今日运势、掷骰、帮你做选择"; }
    @Override
    public String category() { return "🎮 娱乐"; }
}
