package org.windy.xingtubot.module.ai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插话节奏相关组件的单元测试：
 * 规则打分器 / 判定 JSON 契约 / 群友画像 / 复读检测。
 */
class AiRhythmTest {

    // ===== 规则打分器 =====

    private static ReplyNecessityScorer.Context ctx(String prevMsg, String prevAuthor, boolean botSpoke) {
        return new ReplyNecessityScorer.Context("小助手", botSpoke, prevMsg, prevAuthor);
    }

    @Test
    void questionGetsPositiveScore() {
        int s = ReplyNecessityScorer.score("这个插件怎么装", ctx("聊别的", "阿强", false));
        assertTrue(s > 0, "提问应得分: " + s);
    }

    @Test
    void requestWordsGetPositiveScore() {
        int s = ReplyNecessityScorer.score("谁能帮我看下这个报错", ctx("聊别的", "阿强", false));
        assertTrue(s > 0, "求助应得分: " + s);
    }

    @Test
    void botNameMentionScoresHigher() {
        int plain = ReplyNecessityScorer.score("今天天气不错", ctx(null, null, false));
        int named = ReplyNecessityScorer.score("小助手 今天天气不错", ctx(null, null, false));
        assertTrue(named > plain, "点名应比普通消息分高: " + named + " vs " + plain);
    }

    @Test
    void spamIsHeavilyPenalized() {
        int s = ReplyNecessityScorer.score("代练加群 http://example.com", ctx(null, null, false));
        assertTrue(s < 0, "推广应扣分: " + s);
    }

    @Test
    void shortReactionIsPenalized() {
        int s = ReplyNecessityScorer.score("哈哈", ctx(null, null, false));
        assertTrue(s < 0, "短反应应扣分: " + s);
    }

    @Test
    void screenSpamIsPenalized() {
        int s = ReplyNecessityScorer.score("哈哈哈哈哈哈", ctx(null, null, false));
        assertTrue(s < 0, "刷屏应扣分: " + s);
    }

    @Test
    void justSpokeIsPenalized() {
        int s = ReplyNecessityScorer.score("这个插件怎么装", ctx("聊别的", "阿强", true));
        assertTrue(s < 0, "刚说过话应扣分: " + s);
    }

    @Test
    void duplicateOfPreviousIsPenalized() {
        int s = ReplyNecessityScorer.score("服务器又崩了", ctx("服务器又崩了", "阿强", false));
        assertTrue(s < 0, "复读上一条应扣分: " + s);
    }

    @Test
    void emptyMessageScoresLowest() {
        assertTrue(ReplyNecessityScorer.score("", ctx(null, null, false)) <= -100);
        assertTrue(ReplyNecessityScorer.score(null, ctx(null, null, false)) <= -100);
    }

    // ===== 判定 JSON 契约 =====

    @Test
    void judgeParsesJsonContract() {
        ChimeInController.JudgeDecision d = ChimeInController.parse(
                "{\"action\":\"REPLY\",\"social_action\":\"JOKE\",\"delivery_style\":\"玩梗\"}");
        assertTrue(d.reply, "REPLY 应被解析为要插话");
        assertEquals("JOKE", d.socialAction);
        assertEquals("玩梗", d.deliveryStyle);
    }

    @Test
    void judgeParsesJsonWrappedInText() {
        ChimeInController.JudgeDecision d = ChimeInController.parse(
                "好的，我的判断是：{\"action\":\"PASS\"} 就这样");
        assertFalse(d.reply, "夹在文字里的 JSON 也应被解析");
    }

    @Test
    void judgePassActionMeansNoReply() {
        assertFalse(ChimeInController.parse("{\"action\":\"PASS\"}").reply);
    }

    @Test
    void judgeFallsBackToLegacyTextProtocol() {
        assertTrue(ChimeInController.parse("CHIME_IN").reply, "旧的 CHIME_IN 仍应被认出来");
        assertFalse(ChimeInController.parse("NO_REPLY").reply, "旧的 NO_REPLY 仍应被认出来");
    }

    @Test
    void judgeFailsClosed() {
        // 解析不出来 → 保守不插话（宁可少说不可乱说）
        assertFalse(ChimeInController.parse("我看不太清楚").reply);
        assertFalse(ChimeInController.parse("").reply);
        assertFalse(ChimeInController.parse(null).reply);
    }

    @Test
    void judgeRejectsBrokenJson() {
        // 缺右括号、整体不成 JSON → 保守不插话
        assertFalse(ChimeInController.parse("{\"action\": \"REPLY\"").reply, "坏 JSON 应保守处理");
        assertFalse(ChimeInController.parse("{action}").reply, "坏 JSON 应保守处理");
    }

    @Test
    void judgeToleratesLenientJson() {
        // 模型常输出不带引号的值，Gson 宽松模式能认出来，这是好事不是坏事
        assertTrue(ChimeInController.parse("{\"action\": REPLY}").reply, "宽松 JSON 也该认出来");
    }

    // ===== 群友画像 =====

    @Test
    void profileRemembersWhoSaidWhat() {
        UserProfile p = new UserProfile();
        p.record("g1", "u1", "阿强", "今天谁上线", false);
        p.record("g1", "u2", "小雅", "我在", false);
        p.record("g1", "u1", "阿强", "开创造吗", false);

        String text = p.renderForPrompt("g1", 5);
        assertTrue(text.contains("阿强"), "画像应包含阿强: " + text);
        assertTrue(text.contains("小雅"), "画像应包含小雅: " + text);
        assertTrue(text.contains("说过 2 次"), "画像应记下发言次数: " + text);
        assertTrue(text.contains("开创造吗"), "画像应记住最后说过的话: " + text);
    }

    @Test
    void profileIsolatedBetweenGroups() {
        UserProfile p = new UserProfile();
        p.record("g1", "u1", "阿强", "g1 的话", false);
        p.record("g2", "u2", "小雅", "g2 的话", false);

        String g1 = p.renderForPrompt("g1", 5);
        assertTrue(g1.contains("g1 的话"));
        assertFalse(g1.contains("g2 的话"), "不同群的人际关系不该串味: " + g1);
    }

    @Test
    void profileEmptyForUnknownGroup() {
        UserProfile p = new UserProfile();
        assertEquals("", p.renderForPrompt("g-nobody", 5));
    }

    // ===== 复读检测 =====

    @Test
    void identicalTextIsTooSimilar() {
        double s = TextSimilarity.similarity("今天服务器挺卡的", "今天服务器挺卡的");
        assertTrue(s >= 0.99, "完全相同应判为高度相似: " + s);
    }

    @Test
    void similarTemplateIsDetected() {
        double s = TextSimilarity.similarity("确实挺卡的呀", "确实挺卡的哦");
        assertTrue(s > 0.5, "句式雷同应被判出来: " + s);
    }

    @Test
    void differentTextIsNotSimilar() {
        double s = TextSimilarity.similarity("今天服务器挺卡的", "那个插件怎么装啊");
        assertTrue(s < 0.4, "不同内容不该判为相似: " + s);
    }

    @Test
    void tooSimilarChecksEveryPrevious() {
        List<String> prev = Arrays.asList("一句无关的话", "今天服务器挺卡的");
        assertTrue(TextSimilarity.isTooSimilar("今天服务器挺卡的", prev, 0.75));
        assertFalse(TextSimilarity.isTooSimilar("完全另一句话", prev, 0.75));
    }
}
