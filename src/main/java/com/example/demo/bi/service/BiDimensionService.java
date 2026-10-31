package com.example.demo.bi.service;

import com.example.demo.bi.domain.BiActivity;
import com.example.demo.bi.domain.BiChannel;
import com.example.demo.bi.domain.BiProduct;
import com.example.demo.bi.domain.BiRegion;
import com.example.demo.bi.domain.BiSalesRecord;
import com.example.demo.bi.repository.BiActivityRepository;
import com.example.demo.bi.repository.BiChannelRepository;
import com.example.demo.bi.repository.BiProductRepository;
import com.example.demo.bi.repository.BiRegionRepository;
import com.example.demo.bi.repository.BiSalesRecordRepository;
import com.example.demo.bi.support.BiServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 维表通用增删改查逻辑（渠道/商品/活动/区域共用） */
@Service
public class BiDimensionService {

    private final BiChannelRepository channelRepository;
    private final BiProductRepository productRepository;
    private final BiActivityRepository activityRepository;
    private final BiRegionRepository regionRepository;
    private final BiSalesRecordRepository salesRepository;

    public BiDimensionService(BiChannelRepository channelRepository,
                              BiProductRepository productRepository,
                              BiActivityRepository activityRepository,
                              BiRegionRepository regionRepository,
                              BiSalesRecordRepository salesRepository) {
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
        this.activityRepository = activityRepository;
        this.regionRepository = regionRepository;
        this.salesRepository = salesRepository;
    }

    // ================= 通用校验 =================

    /** 主键存在性校验 */
    public void requireChannel(Long id) {
        if (id == null || !channelRepository.existsById(id)) {
            throw new BiServiceException("渠道不存在: id=" + id);
        }
    }

    public void requireProduct(Long id) {
        if (id == null || !productRepository.existsById(id)) {
            throw new BiServiceException("商品不存在: id=" + id);
        }
    }

    public void requireActivity(Long id) {
        if (id == null || !activityRepository.existsById(id)) {
            throw new BiServiceException("活动不存在: id=" + id);
        }
    }

    public void requireRegion(Long id) {
        if (id == null || !regionRepository.existsById(id)) {
            throw new BiServiceException("区域不存在: id=" + id);
        }
    }

    // ================= 渠道 =================

    @Transactional
    public BiChannel createChannel(String name, String code, String type, String status) {
        if (channelRepository.existsByCode(code)) {
            throw new BiServiceException("渠道编码已存在: " + code);
        }
        BiChannel channel = new BiChannel();
        channel.setName(name);
        channel.setCode(code);
        channel.setType(type == null || type.isBlank() ? BiChannel.TYPE_ONLINE : type);
        if (!channel.getType().equals(BiChannel.TYPE_ONLINE) && !channel.getType().equals(BiChannel.TYPE_OFFLINE)) {
            throw new BiServiceException("渠道类型只能是 ONLINE 或 OFFLINE");
        }
        if (status != null && !status.isBlank()) {
            channel.setStatus(status);
        }
        return channelRepository.save(channel);
    }

    @Transactional
    public BiChannel updateChannel(Long id, String name, String code, String type, String status) {
        BiChannel channel = channelRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("渠道不存在: id=" + id));
        if (code != null && !code.isBlank()) {
            channelRepository.findByCode(code)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new BiServiceException("渠道编码已存在: " + code); });
            channel.setCode(code);
        }
        if (name != null && !name.isBlank()) {
            channel.setName(name);
        }
        if (type != null && !type.isBlank()) {
            if (!type.equals(BiChannel.TYPE_ONLINE) && !type.equals(BiChannel.TYPE_OFFLINE)) {
                throw new BiServiceException("渠道类型只能是 ONLINE 或 OFFLINE");
            }
            channel.setType(type);
        }
        if (status != null && !status.isBlank()) {
            channel.setStatus(status);
        }
        return channelRepository.save(channel);
    }

    @Transactional
    public void deleteChannel(Long id) {
        BiChannel channel = channelRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("渠道不存在: id=" + id));
        if (salesRepository.existsByChannelId(id)) {
            throw new BiServiceException("该渠道存在销售流水，无法删除，可改为 DISABLED 停用");
        }
        channelRepository.delete(channel);
    }

    // ================= 商品 =================

    @Transactional
    public BiProduct createProduct(String name, String sku, String category, BigDecimal price, String status) {
        if (productRepository.existsBySku(sku)) {
            throw new BiServiceException("商品 SKU 已存在: " + sku);
        }
        BiProduct product = new BiProduct();
        product.setName(name);
        product.setSku(sku);
        product.setCategory(category == null || category.isBlank() ? "未分类" : category);
        product.setPrice(price);
        if (status != null && !status.isBlank()) {
            product.setStatus(status);
        }
        return productRepository.save(product);
    }

    @Transactional
    public BiProduct updateProduct(Long id, String name, String sku, String category, BigDecimal price, String status) {
        BiProduct product = productRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("商品不存在: id=" + id));
        if (sku != null && !sku.isBlank()) {
            productRepository.findBySku(sku)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new BiServiceException("商品 SKU 已存在: " + sku); });
            product.setSku(sku);
        }
        if (name != null && !name.isBlank()) {
            product.setName(name);
        }
        if (category != null && !category.isBlank()) {
            product.setCategory(category);
        }
        if (price != null) {
            product.setPrice(price);
        }
        if (status != null && !status.isBlank()) {
            product.setStatus(status);
        }
        return productRepository.save(product);
    }

    @Transactional
    public void deleteProduct(Long id) {
        productRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("商品不存在: id=" + id));
        if (salesRepository.existsByProductId(id)) {
            throw new BiServiceException("该商品存在销售流水，无法删除，可改为 OFF_SHELF 下架");
        }
        productRepository.deleteById(id);
    }

    // ================= 活动 =================

    @Transactional
    public BiActivity createActivity(String name, String type, LocalDate startDate, LocalDate endDate,
                                     BigDecimal discount, String status) {
        if (startDate == null || endDate == null) {
            throw new BiServiceException("活动起止日期不能为空");
        }
        if (endDate.isBefore(startDate)) {
            throw new BiServiceException("活动结束日期不能早于开始日期");
        }
        BiActivity activity = new BiActivity();
        activity.setName(name);
        activity.setType(type == null || type.isBlank() ? BiActivity.TYPE_PROMO : type);
        activity.setStartDate(startDate);
        activity.setEndDate(endDate);
        if (discount != null) {
            activity.setDiscount(discount);
        }
        if (status != null && !status.isBlank()) {
            activity.setStatus(status);
        }
        return activityRepository.save(activity);
    }

    @Transactional
    public BiActivity updateActivity(Long id, String name, String type, LocalDate startDate, LocalDate endDate,
                                     BigDecimal discount, String status) {
        BiActivity activity = activityRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("活动不存在: id=" + id));
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BiServiceException("活动结束日期不能早于开始日期");
        }
        if (name != null && !name.isBlank()) {
            activity.setName(name);
        }
        if (type != null && !type.isBlank()) {
            activity.setType(type);
        }
        if (startDate != null) {
            activity.setStartDate(startDate);
        }
        if (endDate != null) {
            activity.setEndDate(endDate);
        }
        if (discount != null) {
            activity.setDiscount(discount);
        }
        if (status != null && !status.isBlank()) {
            activity.setStatus(status);
        }
        return activityRepository.save(activity);
    }

    @Transactional
    public void deleteActivity(Long id) {
        activityRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("活动不存在: id=" + id));
        if (salesRepository.existsByActivityId(id)) {
            throw new BiServiceException("该活动存在销售流水，无法删除，可改为 ENDED 归档");
        }
        activityRepository.deleteById(id);
    }

    // ================= 区域 =================

    @Transactional
    public BiRegion createRegion(String name, String code, Integer level, Long parentId, String status) {
        if (regionRepository.existsByCode(code)) {
            throw new BiServiceException("区域编码已存在: " + code);
        }
        int finalLevel;
        Long finalParent = null;
        if (parentId != null) {
            BiRegion parent = regionRepository.findById(parentId)
                    .orElseThrow(() -> new BiServiceException("上级区域不存在: id=" + parentId));
            if (parent.getLevel() >= 3) {
                throw new BiServiceException("上级区域已是最末级，无法再挂子区域");
            }
            finalLevel = parent.getLevel() + 1;
            finalParent = parent.getId();
        } else {
            finalLevel = level == null ? 1 : level;
        }
        BiRegion region = new BiRegion();
        region.setName(name);
        region.setCode(code);
        region.setLevel(finalLevel);
        region.setParentId(finalParent);
        if (status != null && !status.isBlank()) {
            region.setStatus(status);
        }
        return regionRepository.save(region);
    }

    @Transactional
    public BiRegion updateRegion(Long id, String name, String code, Integer level, Long parentId, String status) {
        BiRegion region = regionRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("区域不存在: id=" + id));
        if (code != null && !code.isBlank()) {
            regionRepository.findByCode(code)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new BiServiceException("区域编码已存在: " + code); });
            region.setCode(code);
        }
        if (name != null && !name.isBlank()) {
            region.setName(name);
        }
        if (level != null) {
            region.setLevel(level);
        }
        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new BiServiceException("区域不能以自己作为上级");
            }
            BiRegion parent = regionRepository.findById(parentId)
                    .orElseThrow(() -> new BiServiceException("上级区域不存在: id=" + parentId));
            region.setParentId(parent.getId());
            region.setLevel(parent.getLevel() + 1);
        }
        if (status != null && !status.isBlank()) {
            region.setStatus(status);
        }
        return regionRepository.save(region);
    }

    @Transactional
    public void deleteRegion(Long id) {
        regionRepository.findById(id)
                .orElseThrow(() -> new BiServiceException("区域不存在: id=" + id));
        if (regionRepository.existsByParentId(id)) {
            throw new BiServiceException("该区域下存在子区域，请先删除子区域");
        }
        if (salesRepository.existsByRegionId(id)) {
            throw new BiServiceException("该区域存在销售流水，无法删除，可改为 DISABLED 停用");
        }
        regionRepository.deleteById(id);
    }

    // ================= 流水 =================

    @Transactional
    public BiSalesRecord createSales(LocalDate statDate, Long channelId, Long productId, Long regionId,
                                     Long activityId, Integer quantity, BigDecimal amount) {
        requireChannel(channelId);
        requireProduct(productId);
        requireRegion(regionId);
        if (activityId != null) {
            requireActivity(activityId);
        }
        if (statDate == null) {
            throw new BiServiceException("销售日期不能为空");
        }
        if (quantity == null || quantity < 1) {
            throw new BiServiceException("销量至少为 1");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BiServiceException("成交额必须大于 0");
        }
        BiSalesRecord record = new BiSalesRecord();
        record.setStatDate(statDate);
        record.setChannelId(channelId);
        record.setProductId(productId);
        record.setRegionId(regionId);
        record.setActivityId(activityId);
        record.setQuantity(quantity);
        record.setAmount(amount);
        return salesRepository.save(record);
    }

    @Transactional
    public void deleteSales(Long id) {
        if (!salesRepository.existsById(id)) {
            throw new BiServiceException("销售流水不存在: id=" + id);
        }
        salesRepository.deleteById(id);
    }

    // ================= 汇总 =================

    /** 按维度（channel/product/region）汇总区间内的销量与成交额 */
    public List<BiSummaryItem> summarize(String group, LocalDate from, LocalDate to) {
        List<Object[]> rows;
        switch (group) {
            case "product" -> rows = salesRepository.sumByProduct(from, to);
            case "region" -> rows = salesRepository.sumByRegion(from, to);
            case "channel" -> rows = salesRepository.sumByChannel(from, to);
            default -> throw new BiServiceException("不支持的汇总维度: " + group + "（可选 channel/product/region）");
        }
        List<BiSummaryItem> items = new ArrayList<>();
        for (Object[] row : rows) {
            Long dimId = ((Number) row[0]).longValue();
            long quantity = row[1] == null ? 0L : ((Number) row[1]).longValue();
            BigDecimal amount = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            items.add(new BiSummaryItem(dimId, resolveName(group, dimId), quantity, amount));
        }
        items.sort(Comparator.comparing(BiSummaryItem::getAmount).reversed());
        return items;
    }

    private String resolveName(String group, Long id) {
        return switch (group) {
            case "product" -> productRepository.findById(id).map(BiProduct::getName).orElse("未知商品");
            case "region" -> regionRepository.findById(id).map(BiRegion::getName).orElse("未知区域");
            default -> channelRepository.findById(id).map(BiChannel::getName).orElse("未知渠道");
        };
    }
}
