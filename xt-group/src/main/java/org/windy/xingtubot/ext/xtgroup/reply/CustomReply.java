package org.windy.xingtubot.ext.xtgroup.reply;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条自定义问答 / 消息模板（来自 replies.yml）。
 */
public class CustomReply {

    public enum Match { EQUALS, CONTAINS, STARTS_WITH, REGEX }

    public enum Type { TEXT, IMAGE, TEXTIMAGE, MARKDOWN }

    public String trigger = "";
    public Match match = Match.EQUALS;
    public Type type = Type.TEXT;
    public String content = "";
    public String file = "";
    public String template = "default";
    public List<Button> buttons = new ArrayList<>();

    public boolean matches(String message) {
        if (trigger == null || trigger.isEmpty()) return false;
        switch (match) {
            case CONTAINS:     return message.contains(trigger);
            case STARTS_WITH:  return message.startsWith(trigger);
            case REGEX:        try { return message.matches(trigger); } catch (Exception e) { return false; }
            case EQUALS:
            default:           return message.equals(trigger);
        }
    }

    /** 按钮定义（replies.yml 里的 buttons 列表项）。 */
    public static class Button {
        public String id = "";
        public String label = "";
        public String data = "";
        public int style = 1;       // 1=普通 2=主要 3=危险
        public String url = "";     // type=1 时跳转的 URL
        public int permission = 2;  // 0=指定用户 1=管理员 2=所有人
        public boolean enter = false;
    }
}
