package org.windy.xingtubot.ext.xtgroup.reply;

import org.windy.xingtubot.common.ai.BotKnowledgeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 replies.yml 的问答暴露为 AI 服务器知识库（{@link BotKnowledgeProvider}）。
 * GroupFeaturesModule 注册到服务总线，xt-ai 懒查询——xt-group / xt-ai 无编译依赖。
 */
public final class ReplyKnowledgeProvider implements BotKnowledgeProvider {

    /** 单条回答截断长度，防止一条长问答吃满 AI 侧的总量预算。 */
    private static final int MAX_CONTENT_CHARS = 300;

    private final CustomReplyService service;

    public ReplyKnowledgeProvider(CustomReplyService service) {
        this.service = service;
    }

    @Override
    public List<String> knowledgeLines() {
        if (service == null) return new ArrayList<>();
        return service.knowledgeLines(MAX_CONTENT_CHARS);
    }
}
