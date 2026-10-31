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
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品维表：维度表之一，标准单价用于销售额统计（口径：不含活动折扣的吊牌价） */
@Entity
@Table(name = "bi_product",
        uniqueConstraints = @UniqueConstraint(name = "uk_product_sku", columnNames = "sku"),
        indexes = @Index(name = "idx_product_category", columnList = "category"))
public class BiProduct {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 商品编码（SKU），唯一 */
    @Column(nullable = false, length = 64)
    private String sku;

    /** 品类：家电/数码/个护/美妆/食品，统计下钻维度 */
    @Column(nullable = false, length = 32)
    private String category;

    /** 标准单价（元），口径基准；实际成交额 = 单价 x 数量 x 活动折扣 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** ENABLED / DISABLED */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = STATUS_ENABLED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
