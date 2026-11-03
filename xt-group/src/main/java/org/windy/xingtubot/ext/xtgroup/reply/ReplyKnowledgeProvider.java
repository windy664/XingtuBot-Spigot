package org.windy.xingtubot.ext.xtgroup.reply;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 把 replies.yml 的问答暴露为 AI 用的服务器知识行。
 * 以 JDK {@link Supplier} 形式注册在服务总线（key: xingtubot.knowledge），
 * xt-ai 按软依赖查找——JDK 类型跨 classloader 可见，无需共同接口、无需反射，
 * xt-group 未装时 xt-ai 查不到即为无知识库。
 */
public final class ReplyKnowledgeProvider implements Supplier<List<String>> {

    /** 单条回答截断长度，防止一条长问答吃满 AI 侧的总量预算。 */
    private static final int MAX_CONTENT_CHARS = 300;

    private final CustomReplyService service;

    public ReplyKnowledgeProvider(CustomReplyService service) {
        this.service = service;
    }

    /** 每行一条「触发词 → 回答」；图片/文字生图条目无文字内容，跳过。 */
    @Override
    public List<String> get() {
        if (service == null) return new ArrayList<>();
        return service.knowledgeLines(MAX_CONTENT_CHARS);
    }
}
