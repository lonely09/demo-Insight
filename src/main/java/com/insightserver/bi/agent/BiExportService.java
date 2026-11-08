package com.insightserver.bi.agent;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * CSV 导出：导出的行就是本次结果快照（按 traceId 取回），
 * 文件头写入指标口径、区间、筛选、权限与结果指纹，便于业务方复核。
 */
@Service
public class BiExportService {

    public String toCsv(BiQueryResult result) {
        BiQueryResult.Meta meta = result.getMeta();
        StringBuilder sb = new StringBuilder(2048);
        sb.append('\uFEFF');
        comment(sb, "指标", meta.metricName() + "(" + meta.metricKey() + ")");
        comment(sb, "口径", meta.caliber());
        comment(sb, "公式", meta.formula());
        comment(sb, "区间", meta.dateFrom() + " 至 " + meta.dateTo() + "（" + meta.dateLabel() + "）");
        comment(sb, "筛选", filters(meta.filters()));
        comment(sb, "角色", meta.permission().roleLabel() + " / 数据范围：" + meta.permission().scopeLabel());
        comment(sb, "查询人", meta.permission().userId());
        comment(sb, "traceId", meta.traceId());
        comment(sb, "结果指纹", meta.resultHash());
        comment(sb, "生成时间", meta.queriedAt().toString());

        if (meta.groupBy() == null) {
            row(sb, "分组", meta.metricName(), "销量(件)", "记录数(条)");
            row(sb, "合计", metricValue(result.getTotals(), meta.metricKey()),
                    result.getTotals().quantity(), result.getTotals().records());
        } else if ("date".equals(meta.groupBy())) {
            row(sb, "日期", meta.metricName(), "销量(件)", "记录数(条)");
            for (BiQueryResult.GroupRow group : result.getGroups()) {
                row(sb, group.name(), metricValue(group, meta.metricKey()),
                        group.quantity(), group.records());
            }
        } else {
            row(sb, "排名", "分组", meta.metricName(), "销量(件)", "记录数(条)", "占比(%)");
            for (BiQueryResult.GroupRow group : result.getGroups()) {
                row(sb, group.rank(), group.name(), metricValue(group, meta.metricKey()),
                        group.quantity(), group.records(), group.share());
            }
        }

        BiQueryResult.Comparison comparison = result.getComparison();
        if (comparison != null) {
            sb.append("\r\n");
            comment(sb, "对比", comparison.label());
            comment(sb, "当前", metricValue(comparison.current(), meta.metricKey()));
            comment(sb, "同期", metricValue(comparison.previous(), meta.metricKey()));
            comment(sb, "增长率", comparison.growthRate() == null ? "不可计算" : comparison.growthRate() + "%");
        }
        return sb.toString();
    }

    public String filename(BiQueryResult result) {
        BiQueryResult.Meta meta = result.getMeta();
        return "bi-" + meta.metricKey() + "-" + meta.dateFrom() + "-" + meta.dateTo()
                + "-" + meta.traceId().substring(0, 8) + ".csv";
    }

    private String filters(List<BiQueryResult.AppliedFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (BiQueryResult.AppliedFilter filter : filters) {
            if ("regionScope".equals(filter.field())) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(filter.label()).append('=').append(String.join("、", filter.values()));
        }
        return sb.isEmpty() ? "无" : sb.toString();
    }

    private Object metricValue(BiQueryResult.Totals totals, String metricKey) {
        return switch (metricKey) {
            case "quantity" -> totals.quantity();
            case "records" -> totals.records();
            default -> totals.amount();
        };
    }

    private Object metricValue(BiQueryResult.GroupRow group, String metricKey) {
        return switch (metricKey) {
            case "quantity" -> group.quantity();
            case "records" -> group.records();
            default -> group.amount();
        };
    }

    private Object metricValue(BigDecimal value, String metricKey) {
        return switch (metricKey) {
            case "quantity", "records" -> value.longValue();
            default -> value;
        };
    }

    private void comment(StringBuilder sb, String key, Object value) {
        sb.append(csv("#" + key)).append(',').append(csv(value)).append("\r\n");
    }

    private void row(StringBuilder sb, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csv(values[i]));
        }
        sb.append("\r\n");
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
