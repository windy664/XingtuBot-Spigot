package org.windy.xingtubot.common.handler;

/**
 * 管理员判定接口。核心框架依赖此接口。
 * 实现由 xt-auth 的 PermissionService 提供。
 */
@FunctionalInterface
public interface PermissionChecker {
    boolean isAdmin(String openid);
}
