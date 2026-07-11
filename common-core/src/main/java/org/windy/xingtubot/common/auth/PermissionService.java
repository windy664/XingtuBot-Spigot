package org.windy.xingtubot.common.auth;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 机器人权限服务：判断某个发消息者（uid）是否为超级管理员。
 *
 * <p>超管列表来自配置（按 uid 配置）。
 * 为以后扩展预留 QQ 号、群管理员等维度，目前实现 uid 维度。
 */
public class PermissionService {

    private final Set<String> adminUids;

    public PermissionService(List<String> adminUids) {
        this.adminUids = adminUids == null
                ? Collections.emptySet()
                : new HashSet<>(adminUids);
    }

    /** 该 uid 是否为超管。 */
    public boolean isAdmin(String uid) {
        return uid != null && adminUids.contains(uid);
    }

    /** 是否配置了任何超管（没配则管理指令对所有人不可用，更安全）。 */
    public boolean hasAnyAdmin() {
        return !adminUids.isEmpty();
    }
}
