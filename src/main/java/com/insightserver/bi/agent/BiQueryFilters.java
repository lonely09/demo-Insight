package com.insightserver.bi.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 自然语言解析出的筛选条件（维度 ID 在解析阶段完成实体对齐） */
public class BiQueryFilters {

    private final Set<Long> channelIds = new LinkedHashSet<>();
    private final Set<Long> productIds = new LinkedHashSet<>();
    private final Set<Long> regionIds = new LinkedHashSet<>();
    private final Set<Long> activityIds = new LinkedHashSet<>();
    private final Set<String> categories = new LinkedHashSet<>();

    /** ONLINE / OFFLINE；null 表示不限线上/线下 */
    private String channelType;

    public void addChannel(Long id) {
        if (id != null) {
            channelIds.add(id);
        }
    }

    public void addProduct(Long id) {
        if (id != null) {
            productIds.add(id);
        }
    }

    public void addRegion(Long id) {
        if (id != null) {
            regionIds.add(id);
        }
    }

    public void addActivity(Long id) {
        if (id != null) {
            activityIds.add(id);
        }
    }

    public void addCategory(String category) {
        if (category != null && !category.isBlank()) {
            categories.add(category);
        }
    }

    public boolean isEmpty() {
        return channelIds.isEmpty() && productIds.isEmpty() && regionIds.isEmpty()
                && activityIds.isEmpty() && categories.isEmpty()
                && (channelType == null || channelType.isBlank());
    }

    public Set<Long> getChannelIds() {
        return channelIds;
    }

    public Set<Long> getProductIds() {
        return productIds;
    }

    public Set<Long> getRegionIds() {
        return regionIds;
    }

    public Set<Long> getActivityIds() {
        return activityIds;
    }

    public Set<String> getCategories() {
        return categories;
    }

    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    /** 仅用于审计/展示的副本，避免后续阶段修改原过滤器 */
    public BiQueryFilters copy() {
        BiQueryFilters copy = new BiQueryFilters();
        copy.channelIds.addAll(channelIds);
        copy.productIds.addAll(productIds);
        copy.regionIds.addAll(regionIds);
        copy.activityIds.addAll(activityIds);
        copy.categories.addAll(categories);
        copy.channelType = channelType;
        return copy;
    }

    public List<String> summaryParts() {
        List<String> parts = new ArrayList<>();
        if (!channelIds.isEmpty()) {
            parts.add("渠道=" + channelIds);
        }
        if (!productIds.isEmpty()) {
            parts.add("商品=" + productIds);
        }
        if (!regionIds.isEmpty()) {
            parts.add("区域=" + regionIds);
        }
        if (!activityIds.isEmpty()) {
            parts.add("活动=" + activityIds);
        }
        if (!categories.isEmpty()) {
            parts.add("品类=" + categories);
        }
        if (channelType != null && !channelType.isBlank()) {
            parts.add("渠道类型=" + channelType);
        }
        return parts;
    }
}
