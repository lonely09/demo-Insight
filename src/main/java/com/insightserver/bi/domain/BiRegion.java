package com.insightserver.bi.domain;

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

import java.time.LocalDateTime;

/** 区域维表：树形结构（parent_id 指向上级），支持统计按大区->省市级下钻 */
@Entity
@Table(name = "bi_region",
        uniqueConstraints = @UniqueConstraint(name = "uk_region_code", columnNames = "code"),
        indexes = {
                @Index(name = "idx_region_parent", columnList = "parent_id"),
                @Index(name = "idx_region_level", columnList = "level")
        })
public class BiRegion {

    public static final int LEVEL_MACRO = 1;  // 大区
    public static final int LEVEL_PROVINCIAL = 2; // 省级/直辖市
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 区域编码，唯一，如 E-SH */
    @Column(nullable = false, length = 64)
    private String code;

    /** 层级：1=大区 2=省/直辖市（本期两级，够用且简单） */
    @Column(nullable = false)
    private int level;

    /** 上级区域ID，顶级区域为 null */
    @Column(name = "parent_id")
    private Long parentId;

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
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
