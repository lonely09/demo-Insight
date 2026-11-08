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

/** 渠道维表：销售发生的渠道（天猫/京东/抖音/线下门店等），统计时的分组/过滤维度之一 */
@Entity
@Table(name = "bi_channel",
        uniqueConstraints = @UniqueConstraint(name = "uk_channel_code", columnNames = "code"),
        indexes = @Index(name = "idx_channel_status", columnList = "status"))
public class BiChannel {

    public static final String TYPE_ONLINE = "ONLINE";
    public static final String TYPE_OFFLINE = "OFFLINE";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 渠道编码，供对话式统计做实体对齐（如 "抖音" -> DOUYIN） */
    @Column(nullable = false, length = 64)
    private String code;

    /** 渠道类型：ONLINE 线上 / OFFLINE 线下 */
    @Column(nullable = false, length = 16)
    private String type;

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
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
