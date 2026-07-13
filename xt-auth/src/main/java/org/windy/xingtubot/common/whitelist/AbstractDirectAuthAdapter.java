package org.windy.xingtubot.common.whitelist;

import org.windy.xingtubot.common.binding.AuthAdapter;

/**
 * 直通认证适配器的平台无关基类。messagePlayer 和 isOnline 由 {@link PlatformPlayerOps} 统一实现，
 * titlePlayer / login / register 因平台 API 差异大留给子类。
 */
public abstract class AbstractDirectAuthAdapter implements AuthAdapter {

    protected final PlatformPlayerOps ops;

    protected AbstractDirectAuthAdapter(PlatformPlayerOps ops) {
        this.ops = ops;
    }

    @Override
    public boolean isOnline(String player) {
        Object p = ops.getPlayer(player);
        return p != null && ops.isOnline(p);
    }

    @Override
    public void messagePlayer(String player, String message) {
        Object p = ops.getPlayer(player);
        if (p != null && ops.isOnline(p)) {
            ops.sendMessage(p, message);
        }
    }

    // login / register / titlePlayer 因平台差异大，由子类实现
}
