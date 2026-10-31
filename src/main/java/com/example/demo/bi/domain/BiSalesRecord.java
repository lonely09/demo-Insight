package com.example.demo.bi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售流水（事实表）：每行 = 某天某渠道某区域卖出某商品一笔。
 * 四个维表 ID 全部冗余存 Long 而非 @ManyToOne，统计扫描更轻，也避免级联问题。
 * 口径：amount = 商品标准单价 x 数量 x（活动期则乘活动折扣），即实际成交额（含折扣）。
 */
@Entity
@Table(name = "bi_sales_record", indexes = {
        @Index(name = "idx_sales_date", columnList = "stat_date"),
        @Index(name = "idx_sales_channel", columnList = "channel_id"),
        @Index(name = "idx_sales_product", columnList = "product_id"),
        @Index(name = "idx_sales_region", columnList = "region_id"),
        @Index(name = "idx_sales_activity", columnList = "activity_id")
})
public class BiSalesRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 统计日期（销售发生日） */
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(nullable = false)
    private Long channelId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long regionId;

    /** 命中的活动，非活动期为 null */
    @Column(name = "activity_id")
    private Long activityId;

    /** 销量（件） */
    @Column(nullable = false)
    private int quantity;

    /** 成交额（元），口径见类注释 */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getRegionId() { return regionId; }
    public void setRegionId(Long regionId) { this.regionId = regionId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
