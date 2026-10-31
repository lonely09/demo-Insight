package com.example.demo.bi.agent;

import java.util.Set;

/**
 * 已认证的查询主体：角色 + 区域数据范围。
 * allowedRegionIds=null 表示不限区域；非 null 时只允许这些区域（通常含下级区域）。
 */
public record BiPrincipal(
        String userId,
        String role,
        String roleLabel,
        Long scopeRegionId,
        String scopeLabel,
        Set<Long> allowedRegionIds,
        boolean exportAllowed) {

    public boolean unrestricted() {
        return allowedRegionIds == null;
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
