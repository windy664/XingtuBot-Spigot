package org.windy.xingtubot.common.module.capability;

import org.windy.xingtubot.common.messenger.MessengerConnectionState;
import org.windy.xingtubot.common.messenger.PlatformMessenger;

/**
 * {@link ProactiveSender} 的惰性实现：平台侧在模块加载前注册本实例，
 * bot 连接成功后调用 {@link #bind(PlatformMessenger)} 填实底层适配器。
 *
 * <p>放在 common-core 供两端复用（包装 {@link PlatformMessenger}，无平台依赖）。
 */
public final class LazyProactiveSender implements ProactiveSender {

    private volatile PlatformMessenger messenger;

    /** bot ready 后由平台注入底层适配器。 */
    public void bind(PlatformMessenger messenger) {
        this.messenger = messenger;
    }

    @Override
    public boolean isReady() {
        PlatformMessenger m = this.messenger;
        return m != null && m.getState().isReady();
    }

    @Override
    public boolean sendGroupMessage(String groupId, String message) {
        PlatformMessenger m = this.messenger;
        if (m == null || !m.getState().isReady()) return false;
        try {
            m.sendGroupMessage(groupId, message);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean sendGroupMarkdown(String groupId, String markdownContent) {
        PlatformMessenger m = this.messenger;
        if (m == null || !m.getState().isReady()) return false;
        try {
            m.sendGroupMarkdown(groupId, markdownContent);
            return true;
        } catch (Exception e) {
            // markdown 失败（如无原生 markdown 权限）→ 回退纯文本
            return sendGroupMessage(groupId, markdownContent);
        }
    }

    @Override
    public boolean sendGroupMarkdownKeyboard(String groupId, String markdownContent, String keyboardJson) {
        PlatformMessenger m = this.messenger;
        if (m == null || !m.getState().isReady()) return false;
        try {
            m.sendGroupMarkdownKeyboard(groupId, markdownContent, keyboardJson);
            return true;
        } catch (Exception e) {
            // 带键盘失败 → 退化为不带键盘的 markdown（再失败回退纯文本）
            return sendGroupMarkdown(groupId, markdownContent);
        }
    }
}
