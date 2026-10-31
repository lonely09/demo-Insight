package com.example.demo.bi.agent;

import com.example.demo.bi.domain.BiRegion;
import com.example.demo.bi.repository.BiRegionRepository;
import com.example.demo.bi.support.BiAccessDeniedException;
import com.example.demo.bi.support.BiServiceException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询主体解析与区域数据权限校验。
 *
 * 生产环境应由网关/SSO 注入身份，本 Demo 用请求头模拟：
 *   X-Bi-User      用户标识（缺省 anonymous）
 *   X-Bi-Role      ADMIN / ANALYST / BUSINESS_USER / VIEWER（缺省 ANALYST）
 *   X-Bi-Region-Id 业务用户的区域范围（区域 ID / 编码 / 名称均可，含全部下级）
 *
 * 合规要点：业务用户必须显式给区域范围；问题里点名了授权范围外的区域会被拒绝，
 * 而不是悄悄裁剪结果。
 */
@Service
public class BiPermissionService {

    private final BiRegionRepository regionRepository;

    public BiPermissionService(BiRegionRepository regionRepository) {
        this.regionRepository = regionRepository;
    }

    public BiPrincipal resolve(String userHeader, String roleHeader, String scopeHeader) {
        String userId = userHeader == null || userHeader.isBlank() ? "anonymous" : userHeader.trim();
        Role role = Role.parse(roleHeader);

        if (role != Role.BUSINESS_USER) {
            return new BiPrincipal(userId, role.name(), role.label, null, "全部区域", null, role.exportAllowed);
        }

        if (scopeHeader == null || scopeHeader.isBlank()) {
            throw new BiAccessDeniedException("业务用户必须通过 X-Bi-Region-Id 指定区域权限范围");
        }

        BiRegion region = findRegion(scopeHeader.trim());
        Set<Long> allowed = expandDescendants(List.of(region.getId()), regionRepository.findAll());
        return new BiPrincipal(userId, role.name(), role.label, region.getId(),
                region.getName() + "（含下级）", allowed, role.exportAllowed);
    }

    /**
     * 把问题里的区域筛选与主体授权范围求交。
     * requested 为空时，业务用户自动落到授权范围；非空时只要有一点越权就拒绝。
     */
    public Set<Long> effectiveRegionIds(BiPrincipal principal, Collection<Long> requested,
                                        List<BiRegion> allRegions) {
        Set<Long> expanded = expandDescendants(requested, allRegions);
        if (principal.allowedRegionIds() == null) {
            return expanded.isEmpty() ? null : expanded;
        }
        if (expanded.isEmpty()) {
            return principal.allowedRegionIds();
        }
        if (!principal.allowedRegionIds().containsAll(expanded)) {
            throw new BiAccessDeniedException("查询区域超出授权范围：" + principal.scopeLabel());
        }
        return expanded;
    }

    public Set<Long> expandDescendants(Collection<Long> roots, List<BiRegion> allRegions) {
        Set<Long> ids = new LinkedHashSet<>();
        if (roots == null) {
            return ids;
        }
        for (Long root : roots) {
            if (root != null) {
                ids.add(root);
            }
        }
        if (ids.isEmpty()) {
            return ids;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BiRegion region : allRegions) {
                if (region.getParentId() != null && ids.contains(region.getParentId()) && ids.add(region.getId())) {
                    changed = true;
                }
            }
        }
        return ids;
    }

    public BiRegion findRegion(String key) {
        if (key.matches("\\d+")) {
            return regionRepository.findById(Long.parseLong(key))
                    .orElseThrow(() -> new BiServiceException("区域不存在: id=" + key));
        }
        return regionRepository.findByCode(key)
                .or(() -> regionRepository.findAll().stream()
                        .filter(r -> key.equals(r.getName()))
                        .findFirst())
                .orElseThrow(() -> new BiServiceException("区域不存在: " + key));
    }

    private enum Role {
        ADMIN("管理员", true),
        ANALYST("分析师", true),
        BUSINESS_USER("业务用户", true),
        VIEWER("只读用户", false);

        private final String label;
        private final boolean exportAllowed;

        Role(String label, boolean exportAllowed) {
            this.label = label;
            this.exportAllowed = exportAllowed;
        }

        static Role parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ANALYST;
            }
            String value = raw.trim().toUpperCase();
            return switch (value) {
                case "ADMIN", "管理员" -> ADMIN;
                case "ANALYST", "分析师" -> ANALYST;
                case "BUSINESS_USER", "BUSINESS", "业务用户", "业务" -> BUSINESS_USER;
                case "VIEWER", "READONLY", "只读", "只读用户" -> VIEWER;
                default -> throw new BiServiceException("未知角色: " + raw
                        + "（可选 ADMIN / ANALYST / BUSINESS_USER / VIEWER）");
            };
        }
    }
}
