package com.example.demo.bi.agent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话式统计的完整结果：自然语言结论 + 结构化数据 + 口径/权限/追溯元信息。
 * 同一个对象会原样落审计（resultJson），导出时按 traceId 取回，保证"看到的数"和"导出的数"一致。
 */
public class BiQueryResult {

    private String answer;
    private Totals totals;
    private List<GroupRow> groups = new ArrayList<>();
    private List<TrendPoint> trend = new ArrayList<>();
    private Comparison comparison;
    private List<DrillNode> drill = new ArrayList<>();
    private Meta meta;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Totals getTotals() {
        return totals;
    }

    public void setTotals(Totals totals) {
        this.totals = totals;
    }

    public List<GroupRow> getGroups() {
        return groups;
    }

    public void setGroups(List<GroupRow> groups) {
        this.groups = groups == null ? new ArrayList<>() : groups;
    }

    public List<TrendPoint> getTrend() {
        return trend;
    }

    public void setTrend(List<TrendPoint> trend) {
        this.trend = trend == null ? new ArrayList<>() : trend;
    }

    public Comparison getComparison() {
        return comparison;
    }

    public void setComparison(Comparison comparison) {
        this.comparison = comparison;
    }

    public List<DrillNode> getDrill() {
        return drill;
    }

    public void setDrill(List<DrillNode> drill) {
        this.drill = drill == null ? new ArrayList<>() : drill;
    }

    public Meta getMeta() {
        return meta;
    }

    public void setMeta(Meta meta) {
        this.meta = meta;
    }

    public record Totals(BigDecimal amount, long quantity, long records) {
    }

    public record GroupRow(int rank, String key, String name, BigDecimal amount, long quantity,
                           long records, BigDecimal share) {
    }

    public record TrendPoint(String label, LocalDate date, BigDecimal amount, long quantity, long records) {
    }

    public record GroupGrowth(String name, BigDecimal current, BigDecimal previous, BigDecimal growthRate) {
    }

    public record Comparison(String type, String label, LocalDate priorFrom, LocalDate priorTo,
                             BigDecimal current, BigDecimal previous, BigDecimal growthRate,
                             String note, List<GroupGrowth> groups) {
    }

    public record DrillNode(Long id, String name, BigDecimal amount, long quantity, long records,
                            BigDecimal share, List<DrillNode> children) {
    }

    public record AppliedFilter(String field, String label, List<String> values) {
    }

    public record PermissionView(String userId, String role, String roleLabel, String scopeLabel,
                                 List<String> scopeRegions, boolean exportAllowed) {
    }

    public record Meta(String traceId, String metricKey, String metricName, String metricUnit,
                       String caliber, String formula, LocalDate dateFrom, LocalDate dateTo,
                       String dateLabel, String groupBy, String groupLabel, String compare,
                       boolean share, boolean drill, List<AppliedFilter> filters, int rowCount,
                       String resultHash, LocalDateTime queriedAt, String dataSource,
                       PermissionView permission) {
    }
}
