package com.example.demo.bi.agent;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把结构化结果写成业务人员能直接读的中文结论。
 * 结论中的指标名、口径、单位全部来自 result.meta（源头是 BiMetric），不在这里另写一套。
 */
@Component
public class BiAnswerComposer {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat INTEGER = new DecimalFormat("#,##0");

    public void compose(BiQueryResult result) {
        BiQueryResult.Meta meta = result.getMeta();
        BiQueryResult.Totals totals = result.getTotals();
        StringBuilder sb = new StringBuilder();

        sb.append("在 ").append(meta.dateLabel())
                .append("（").append(meta.dateFrom()).append(" 至 ").append(meta.dateTo()).append("）");
        String filterText = filterText(meta.filters());
        if (!filterText.isBlank()) {
            sb.append("，筛选 ").append(filterText);
        }
        sb.append(" 内：");

        if (totals.records() == 0) {
            sb.append("没有匹配的销售流水。可能原因：该区间无数据、筛选条件过窄，或当前角色的区域权限不包含所选区域。");
            appendTrace(sb, meta);
            result.setAnswer(sb.toString());
            return;
        }

        if ("amount".equals(meta.metricKey())) {
            sb.append(meta.metricName()).append("合计 ").append(money(totals.amount()))
                    .append("，销量 ").append(integer(totals.quantity())).append(" 件")
                    .append("，共 ").append(integer(totals.records())).append(" 条流水。");
        } else if ("quantity".equals(meta.metricKey())) {
            sb.append(meta.metricName()).append("合计 ").append(integer(totals.quantity())).append(" 件")
                    .append("，成交额 ").append(money(totals.amount()))
                    .append("，共 ").append(integer(totals.records())).append(" 条流水。");
        } else {
            sb.append(meta.metricName()).append("共 ").append(integer(totals.records())).append(" 条")
                    .append("，成交额 ").append(money(totals.amount()))
                    .append("，销量 ").append(integer(totals.quantity())).append(" 件。");
        }

        appendGroups(sb, result);
        appendComparison(sb, result);
        appendDrill(sb, result);
        appendTrace(sb, meta);

        result.setAnswer(sb.toString());
    }

    private void appendGroups(StringBuilder sb, BiQueryResult result) {
        List<BiQueryResult.GroupRow> groups = result.getGroups();
        if (groups == null || groups.isEmpty()) {
            return;
        }
        BiQueryResult.Meta meta = result.getMeta();
        if ("date".equals(meta.groupBy())) {
            BiQueryResult.TrendPoint peak = null;
            for (BiQueryResult.TrendPoint p : result.getTrend()) {
                if (peak == null || compare(p.amount(), peak.amount()) > 0) {
                    peak = p;
                }
            }
            sb.append("按").append(meta.groupLabel()).append("共 ").append(groups.size()).append(" 个点");
            if (peak != null) {
                sb.append("，峰值在 ").append(peak.label()).append("（")
                        .append(fmt(peak.amount(), peak.quantity(), peak.records(), meta.metricKey()))
                        .append("）");
            }
            sb.append("。");
            return;
        }

        List<BiQueryResult.GroupRow> top = groups.stream().limit(3).toList();
        sb.append("按").append(meta.groupLabel()).append("看，");
        for (int i = 0; i < top.size(); i++) {
            BiQueryResult.GroupRow row = top.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(row.name()).append(" ").append(fmt(row.amount(), row.quantity(), row.records(), meta.metricKey()));
            if (meta.share() || result.getGroups().size() > 1) {
                sb.append("（占 ").append(row.share()).append("%）");
            }
        }
        sb.append("。");
        if (groups.size() > top.size()) {
            sb.append("完整 Top").append(groups.size()).append(" 见结果表。");
        }
    }

    private void appendComparison(StringBuilder sb, BiQueryResult result) {
        BiQueryResult.Comparison comparison = result.getComparison();
        if (comparison == null) {
            return;
        }
        sb.append(comparison.label()).append("：当前 ")
                .append(fmtValue(comparison.current(), result.getMeta().metricKey()))
                .append("，同期 ")
                .append(fmtValue(comparison.previous(), result.getMeta().metricKey()));
        if (comparison.growthRate() != null) {
            sb.append("，").append(comparison.growthRate().signum() >= 0 ? "增长 " : "下降 ")
                    .append(comparison.growthRate().abs()).append("%");
        }
        sb.append("（").append(comparison.note()).append("）。");
    }

    private void appendDrill(StringBuilder sb, BiQueryResult result) {
        List<BiQueryResult.DrillNode> drill = result.getDrill();
        if (drill == null || drill.isEmpty()) {
            return;
        }
        BiQueryResult.DrillNode root = drill.get(0);
        sb.append("区域下钻：").append(root.name()).append(" ")
                .append(fmt(root.amount(), root.quantity(), root.records(), result.getMeta().metricKey()));
        if (root.children() != null && !root.children().isEmpty()) {
            sb.append("，其中");
            List<BiQueryResult.DrillNode> children = root.children().stream().limit(3).toList();
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append("、");
                }
                BiQueryResult.DrillNode child = children.get(i);
                sb.append(child.name()).append(" ")
                        .append(fmt(child.amount(), child.quantity(), child.records(), result.getMeta().metricKey()));
            }
        }
        sb.append("。");
    }

    private void appendTrace(StringBuilder sb, BiQueryResult.Meta meta) {
        sb.append(" 口径：").append(meta.caliber()).append("。");
        sb.append(" 追溯：traceId=").append(meta.traceId())
                .append("，结果指纹=").append(meta.resultHash(), 0, 12).append("…");
        if (meta.permission() != null) {
            sb.append("，查询人=").append(meta.permission().userId())
                    .append("（").append(meta.permission().roleLabel()).append("）")
                    .append("，数据范围=").append(meta.permission().scopeLabel());
        }
        sb.append("。");
    }

    private String filterText(List<BiQueryResult.AppliedFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        return filters.stream()
                .filter(f -> !"regionScope".equals(f.field()))
                .map(f -> f.label() + "=" + String.join("、", f.values()))
                .collect(Collectors.joining("，"));
    }

    private String fmt(BigDecimal amount, long quantity, long records, String metricKey) {
        return switch (metricKey == null ? "amount" : metricKey) {
            case "quantity" -> integer(quantity) + " 件";
            case "records" -> integer(records) + " 条";
            default -> money(amount);
        };
    }

    private String fmtValue(BigDecimal value, String metricKey) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return switch (metricKey == null ? "amount" : metricKey) {
            case "quantity" -> integer(safe.longValue()) + " 件";
            case "records" -> integer(safe.longValue()) + " 条";
            default -> money(safe);
        };
    }

    private String money(BigDecimal value) {
        return "¥" + MONEY.format(value == null ? BigDecimal.ZERO : value);
    }

    private String integer(long value) {
        return INTEGER.format(value);
    }

    private int compare(BigDecimal a, BigDecimal b) {
        BigDecimal left = a == null ? BigDecimal.ZERO : a;
        BigDecimal right = b == null ? BigDecimal.ZERO : b;
        return left.compareTo(right);
    }
}
