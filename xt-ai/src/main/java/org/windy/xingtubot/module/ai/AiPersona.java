package org.windy.xingtubot.module.ai;

import org.windy.xingtubot.common.config.BotConfig;
import org.windy.xingtubot.common.runtime.BotRuntimeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 人格卡：把「AI 是谁」拆成几个互不干扰的字段，而不是糊成一大段提示词。
 *
 * <p>为什么要拆：研究（以及国内最成熟的同类项目 MaiBot）都把人格拆成
 * 身份 / 行为风格 / 表达风格 / 群聊约束 / 备用风格几块，每块只管一件事，
 * 改的时候才改得动。而且<b>身份反差比形容词有效</b>——
 * 「在服务器混了三年的老玩家」比「幽默风趣、善于交流」更能压住 AI 腔。
 *
 * <h3>配置读取的跨平台坑</h3>
 * <ul>
 *   <li>Spigot 端（{@code SpigotConfig}）走 Bukkit 的 {@code FileConfiguration}，
 *       支持 {@code persona.identity} 这种点号路径；</li>
 *   <li>Velocity / BungeeCord 端（附属插件用的 {@code YamlBotConfig}）是
 *       <b>扁平 map</b>，点号路径取不到值。</li>
 * </ul>
 * 所以人格的<b>标量</b>统一放 {@code persona:} 段里用 {@code getStringMap("persona")} 读
 * （两端行为一致），<b>列表</b>放顶层用 {@code getStringList(...)} 读（顶层 key 两端都平）。
 */
final class AiPersona {

    /** 一条示例对话。 */
    static final class FewShot {
        final String user;
        final String assistant;

        FewShot(String user, String assistant) {
            this.user = user;
            this.assistant = assistant;
        }
    }

    private final boolean enabled;
    private final String name;
    private final String identity;
    private final String behavior;
    private final String reply;
    private final String groupRules;
    private final List<String> catchphrases;
    private final List<String> forbidden;
    private final String altStyleText;
    private final double altStyleProbability;
    private final List<FewShot> fewShot;

    private AiPersona(boolean enabled, String name, String identity, String behavior, String reply,
                      String groupRules, List<String> catchphrases, List<String> forbidden,
                      String altStyleText, double altStyleProbability, List<FewShot> fewShot) {
        this.enabled = enabled;
        this.name = name;
        this.identity = identity;
        this.behavior = behavior;
        this.reply = reply;
        this.groupRules = groupRules;
        this.catchphrases = catchphrases;
        this.forbidden = forbidden;
        this.altStyleText = altStyleText;
        this.altStyleProbability = altStyleProbability;
        this.fewShot = fewShot;
    }

    static AiPersona fromConfig(BotConfig config) {
        Map<String, String> p = config.getStringMap("persona");

        boolean enabled = Boolean.parseBoolean(p.getOrDefault("enabled", "true"));

        // 名字：留空 = 取 QQ 机器人的实际昵称
        String name = p.getOrDefault("name", "").trim();
        if (name.isEmpty()) name = BotRuntimeState.getBotName();

        // 旧版单字段 personality 继续有效：当身份描述的兜底，
        // 这样老配置升级后不用改任何东西也有效果。
        String legacy = config.getStringResolved("personality", "").trim();

        String identity = p.getOrDefault("identity", "").trim();
        if (identity.isEmpty()) identity = legacy;

        String behavior = p.getOrDefault("behavior", "").trim();
        if (behavior.isEmpty()) behavior = DEFAULT_BEHAVIOR;

        String reply = p.getOrDefault("reply", "").trim();
        if (reply.isEmpty()) reply = DEFAULT_REPLY;

        String groupRules = p.getOrDefault("group-rules", "").trim();

        List<String> catchphrases = config.getStringList("persona-catchphrases");
        List<String> forbidden = config.getStringList("persona-forbidden");

        String altStyleText = p.getOrDefault("alt-style-text", "").trim();
        double altProb = parseDouble(p.get("alt-style-probability"), 0.1);

        List<FewShot> shots = new ArrayList<>();
        for (Map<String, Object> entry : config.getStringMapList("few-shot")) {
            Object u = entry.get("user");
            Object a = entry.get("assistant");
            if (u == null || a == null) continue;
            String us = String.valueOf(u).trim();
            String as = String.valueOf(a).trim();
            if (us.isEmpty() || as.isEmpty()) continue;
            shots.add(new FewShot(us, as));
        }

        return new AiPersona(enabled, name, identity, behavior, reply, groupRules,
                catchphrases, forbidden, altStyleText, altProb, shots);
    }

    boolean isEnabled() {
        return enabled;
    }

    String getName() {
        return name;
    }

    String getIdentity() {
        return identity;
    }

    String getBehavior() {
        return behavior;
    }

    String getReply() {
        return reply;
    }

    String getGroupRules() {
        return groupRules;
    }

    List<String> getCatchphrases() {
        return catchphrases == null ? Collections.<String>emptyList() : catchphrases;
    }

    List<String> getForbidden() {
        return forbidden == null ? Collections.<String>emptyList() : forbidden;
    }

    String getAltStyleText() {
        return altStyleText;
    }

    double getAltStyleProbability() {
        return altStyleProbability;
    }

    List<FewShot> getFewShot() {
        return fewShot == null ? Collections.<FewShot>emptyList() : fewShot;
    }

    /** 备用风格这次要不要用。 */
    boolean useAltStyle() {
        return !altStyleText.isEmpty()
                && altStyleProbability > 0
                && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < altStyleProbability;
    }

    // ==================== 默认值 ====================

    static final String DEFAULT_BEHAVIOR =
            "留意群友在聊什么，话题跟你有关、明显冷场、或者有人求助时才接；"
                    + "别人在私聊就当没看见；被 @ 一定回；实在没什么想说的就闭嘴。";

    static final String DEFAULT_REPLY =
            "短句为主，一两句说完；口语化，可以用「确实」「行吧」「不是」这类词；"
                    + "不写书面语，不列点，不写总结。";

    private static double parseDouble(String raw, double def) {
        if (raw == null || raw.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
