package org.windy.xingtubot.common.module.capability;

import org.windy.xingtubot.common.qq.QqOpenApiClient;
import org.windy.xingtubot.common.util.GroupTargets;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link ProactiveSender} 的惰性实现：平台侧在模块加载前注册本实例，
 * bot 连接成功后调用 {@link #bind(QqOpenApiClient)} 填实底层 apiClient。
 *
 * <p>放在 common-core 供两端复用（只包装 {@link QqOpenApiClient}，无平台依赖）。
 */
public final class LazyProactiveSender implements ProactiveSender {

    private volatile QqOpenApiClient client;
    private volatile Supplier<List<String>> allowedGroupsSupplier = () -> Collections.singletonList("*");

    /** bot ready 后由平台注入底层客户端。 */
    public void bind(QqOpenApiClient client) {
        this.client = client;
    }

    /** Core allowed-groups is the hard boundary for all proactive sends. */
    public void setAllowedGroupsSupplier(Supplier<List<String>> allowedGroupsSupplier) {
        this.allowedGroupsSupplier = allowedGroupsSupplier != null
                ? allowedGroupsSupplier : () -> Collections.singletonList("*");
    }

    @Override
    public boolean isReady() {
        return client != null;
    }

    @Override
    public boolean sendGroupMessage(String groupOpenId, String message) {
        if (!allowed(groupOpenId)) return false;
        QqOpenApiClient c = this.client;
        if (c == null) return false;
        try {
            c.sendProactiveGroupMessage(groupOpenId, message);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean sendGroupMarkdown(String groupOpenId, String markdownContent) {
        if (!allowed(groupOpenId)) return false;
        QqOpenApiClient c = this.client;
        if (c == null) return false;
        try {
            c.sendProactiveGroupMarkdown(groupOpenId, markdownContent);
            return true;
        } catch (Exception e) {
            // markdown 失败（如无原生 markdown 权限）→ 回退纯文本
            return sendGroupMessage(groupOpenId, markdownContent);
        }
    }

    @Override
    public boolean sendGroupMarkdownKeyboard(String groupOpenId, String markdownContent, String keyboardJson) {
        if (!allowed(groupOpenId)) return false;
        QqOpenApiClient c = this.client;
        if (c == null) return false;
        try {
            c.sendProactiveGroupMarkdown(groupOpenId, markdownContent, keyboardJson);
            return true;
        } catch (Exception e) {
            // 带键盘失败 → 退化为不带键盘的 markdown（再失败回退纯文本）
            return sendGroupMarkdown(groupOpenId, markdownContent);
        }
    }

    private boolean allowed(String groupOpenId) {
        return GroupTargets.isAllowedByCore(allowedGroupsSupplier.get(), groupOpenId);
    }
}
