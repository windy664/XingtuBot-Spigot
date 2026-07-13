package org.windy.xingtubot.auth;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 机器人权限服务：判断某个发消息者（openid）是否为超级管理员。
 *
 * <p>超管列表来自配置（按 openid 配置，webhook 自带、不可伪造、无需绑定）。
 * 为以后扩展预留 QQ 号、群管理员等维度，目前实现 openid 维度。
 */
public class PermissionService {

    private final Set<String> adminOpenids;

    public PermissionService(List<String> adminOpenids) {
        this.adminOpenids = adminOpenids == null
                ? Collections.emptySet()
                : new HashSet<>(adminOpenids);
    }

    /** 该 openid 是否为超管。 */
    public boolean isAdmin(String openid) {
        return openid != null && adminOpenids.contains(openid);
    }

    /** 是否配置了任何超管（没配则管理指令对所有人不可用，更安全）。 */
    public boolean hasAnyAdmin() {
        return !adminOpenids.isEmpty();
    }
}
