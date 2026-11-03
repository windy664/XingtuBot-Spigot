package org.windy.xingtubot.common.ai;

import java.util.List;

/**
 * 服务器知识库：把服主配置的群问答（replies.yml 等）以「触发词 → 回答」行
 * 提供给 AI 模块注入系统词，让 AI 回答「换个问法」的服务器问题时与配置口径一致。
 *
 * <p>由 xt-group 实现并经 {@code ModuleContext.registerService} 注册，
 * xt-ai 通过 {@code ModuleContext.getService(BotKnowledgeProvider.class)} 懒查询
 * （每次构建提示词时现查，不缓存 null，与模块加载顺序无关）。
 */
public interface BotKnowledgeProvider {

    /** 每行一条「触发词 → 回答」；无可用内容时返回空列表。 */
    List<String> knowledgeLines();
}
