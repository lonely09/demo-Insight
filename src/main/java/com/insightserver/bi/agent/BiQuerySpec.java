package com.insightserver.bi.agent;

import java.time.LocalDate;

/**
 * 自然语言问题解析后的结构化查询规格。
 * 解析器只负责把"人话"变成这份规格；统计引擎只认这份规格，便于追溯和复算。
 */
public class BiQuerySpec {

    private String question;
    private String metricKey = "amount";
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String dateLabel = "最近30天";

    /** null / channel / product / category / region / activity / date */
    private String groupBy;

    /** groupBy=date 时的粒度：day / week / month */
    private String grain = "day";

    /** null / mom / yoy */
    private String compare;

    private boolean share;
    private boolean drill;
    private boolean ascending;
    private int limit = 10;

    private BiQueryFilters filters = new BiQueryFilters();

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(LocalDate dateFrom) {
        this.dateFrom = dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public void setDateTo(LocalDate dateTo) {
        this.dateTo = dateTo;
    }

    public String getDateLabel() {
        return dateLabel;
    }

    public void setDateLabel(String dateLabel) {
        this.dateLabel = dateLabel;
    }

    public String getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(String groupBy) {
        this.groupBy = groupBy == null || groupBy.isBlank() ? null : groupBy;
    }

    public String getGrain() {
        return grain;
    }

    public void setGrain(String grain) {
        this.grain = grain;
    }

    public String getCompare() {
        return compare;
    }

    public void setCompare(String compare) {
        this.compare = compare;
    }

    public boolean isShare() {
        return share;
    }

    public void setShare(boolean share) {
        this.share = share;
    }

    public boolean isDrill() {
        return drill;
    }

    public void setDrill(boolean drill) {
        this.drill = drill;
    }

    public boolean isAscending() {
        return ascending;
    }

    public void setAscending(boolean ascending) {
        this.ascending = ascending;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = Math.max(1, Math.min(limit, 50));
    }

    public BiQueryFilters getFilters() {
        return filters;
    }

    public void setFilters(BiQueryFilters filters) {
        this.filters = filters == null ? new BiQueryFilters() : filters;
    }

    public String groupLabel() {
        return switch (groupBy == null ? "" : groupBy) {
            case "channel" -> "渠道";
            case "product" -> "商品";
            case "category" -> "品类";
            case "region" -> "区域";
            case "activity" -> "活动";
            case "date" -> "日期";
            default -> "合计";
        };
    }
}
