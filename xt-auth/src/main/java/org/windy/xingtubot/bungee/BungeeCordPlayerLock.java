package org.windy.xingtubot.bungee;

import net.md_5.bungee.api.ProxyServer;
import org.windy.xingtubot.common.binding.BindingService;
import org.windy.xingtubot.common.whitelist.AbstractPlayerLock;
import org.windy.xingtubot.common.whitelist.PlatformPlayerOps;

/**
 * BungeeCord 端玩家登录锁管理器。
 */
public class BungeeCordPlayerLock extends AbstractPlayerLock {

    private final PlatformPlayerOps ops;

    public BungeeCordPlayerLock(ProxyServer proxy, BindingService bindingService) {
        super(bindingService);
        this.ops = new BungeeCordPlayerOps(proxy);
    }

    @Override
    protected PlatformPlayerOps ops() {
        return ops;
    }
}
