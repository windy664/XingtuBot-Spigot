package org.windy.xingtubot.common.service;

import java.util.List;

/**
 * 文本翻译能力的平台/实现无关接口。
 *
 * <p>common-core 内的消费方（Modrinth 搜索、模组更新通知等）只依赖此接口；
 * 具体实现（如 module-translate 的 {@code BaiduTranslateService}）由功能模块提供，
 * 并经 {@link org.windy.xingtubot.common.module.ModuleContext#registerService} 注册，
 * 消费模块通过 {@code getService(Translator.class)} 获取。这样 common-core 不再
 * 反向依赖 module-translate（避免模块循环依赖）。
 *
 * <p>所有方法在未启用或失败时应返回原文，不抛异常。
 */
public interface Translator {

    /** 翻译是否可用（已配置凭证）。 */
    boolean isEnabled();

    /** 英→中翻译单条文本；失败/未启用返回原文。 */
    String translateEnToZh(String text);

    /** 中→英翻译单条文本；失败/未启用返回原文。 */
    String translateZhToEn(String text);

    /** 批量英→中翻译（减少 API 调用）；失败/未启用返回原列表。 */
    List<String> batchTranslateEnToZh(List<String> texts);
}
