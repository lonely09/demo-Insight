package com.example.demo.bi.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对话式统计审计留痕：一次提问 = 一条记录。
 * 保存"谁、问了什么、按什么口径/区间/筛选算的、结果指纹、结果快照、是否导出"，
 * 支撑结果可追溯与合规审计。resultJson 是导出时的同一份数据，避免二次查询口径漂移。
 */
@Entity
@Table(name = "bi_audit_log", indexes = {
        @Index(name = "idx_bi_audit_trace", columnList = "trace_id", unique = true),
        @Index(name = "idx_bi_audit_user", columnList = "user_id"),
        @Index(name = "idx_bi_audit_created", columnList = "created_at")
})
public class BiAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64, unique = true)
    private String traceId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "scope_label", length = 256)
    private String scopeLabel;

    @Lob
    @Column(nullable = false)
    private String question;

    @Column(name = "metric_key", length = 64)
    private String metricKey;

    @Column(name = "metric_name", length = 128)
    private String metricName;

    @Lob
    @Column(name = "metric_caliber")
    private String metricCaliber;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "date_label", length = 128)
    private String dateLabel;

    @Column(name = "group_by", length = 32)
    private String groupBy;

    @Column(name = "compare_type", length = 16)
    private String compareType;

    @Lob
    @Column(name = "filters_json")
    private String filtersJson;

    @Lob
    @Column(name = "filters_text")
    private String filtersText;

    @Column(name = "row_count")
    private int rowCount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "total_quantity")
    private long totalQuantity;

    @Lob
    private String answer;

    @Column(name = "result_hash", length = 64)
    private String resultHash;

    @Lob
    @Column(name = "result_json")
    private String resultJson;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(nullable = false, length = 16)
    private String status;

    @Lob
    @Column(name = "error_message")
    private String errorMessage;

    @Column(nullable = false)
    private boolean exported;

    @Column(name = "exported_at")
    private LocalDateTime exportedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getScopeLabel() { return scopeLabel; }
    public void setScopeLabel(String scopeLabel) { this.scopeLabel = scopeLabel; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getMetricCaliber() { return metricCaliber; }
    public void setMetricCaliber(String metricCaliber) { this.metricCaliber = metricCaliber; }
    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }
    public String getDateLabel() { return dateLabel; }
    public void setDateLabel(String dateLabel) { this.dateLabel = dateLabel; }
    public String getGroupBy() { return groupBy; }
    public void setGroupBy(String groupBy) { this.groupBy = groupBy; }
    public String getCompareType() { return compareType; }
    public void setCompareType(String compareType) { this.compareType = compareType; }
    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }
    public String getFiltersText() { return filtersText; }
    public void setFiltersText(String filtersText) { this.filtersText = filtersText; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public long getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(long totalQuantity) { this.totalQuantity = totalQuantity; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getResultHash() { return resultHash; }
    public void setResultHash(String resultHash) { this.resultHash = resultHash; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isExported() { return exported; }
    public void setExported(boolean exported) { this.exported = exported; }
    public LocalDateTime getExportedAt() { return exportedAt; }
    public void setExportedAt(LocalDateTime exportedAt) { this.exportedAt = exportedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
