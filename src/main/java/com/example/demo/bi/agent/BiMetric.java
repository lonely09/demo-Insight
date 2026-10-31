package com.example.demo.bi.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标口径单一来源(口径统一):对话 Agent、统计端点、CSV 导出均取指标名与
 * 口径文本从这里,所以同一指标在任何回答中同名、同定义、同公式。
 * 口径约定(与 BiSalesRecord 类注释):amount 元,已包含活动折扣,即实际成交额;
 * quantity 件。任何新增指标必须登记 here,不得在 Agent 代码旁另行硬码。
 */
@Component
public class BiMetric {

    /** 指标登记表: key -> [显示名, 口径文本, 取数公式/数据来源] */
    private static final Map<String, String[]> CATALOG = catalogTable();
    /** 下钻动作表: 下钻名 -> 统计分组（channel/product/region/activity/date） */
    private static final Map<String, String> DRILL = drillTable();

    /** 对比动作表: 对比名 -> 口径文本（同比/环比） */
    private static final Map<String, String> COMPARE = compareTable();

    /** 未指定指标时默认口径: 成交额 */
    private static final String DEFAULT_METRIC = "amount";

    /** 注册顺序（LinkedHashMap 保稳展示，导出/端点同一顺序） */
    public List<String> keys() {
        return new ArrayList<>(CATALOG.keySet());
    }

    public List<String> drillKeys() {
        return new ArrayList<>(DRILL.keySet());
    }

    public List<String> compareKeys() {
        return new ArrayList<>(COMPARE.keySet());
    }

    public static boolean supports(String key) {
        return key != null && CATALOG.containsKey(key);
    }

    /** 指标定义快照; key 未登记时 null,调用者负责提示可用 keys */
    public BiMetricDef metric(String key) {
        String[] def = key == null ? null : CATALOG.get(key);
        return def == null ? null : new BiMetricDef(key, def[0], def[1], def[2]);
    }

    public BiMetricDef metricOrDefault(String key) {
        BiMetricDef def = metric(key);
        return def == null ? metric(DEFAULT_METRIC) : def;
    }

    public String name(String key) {
        return metricOrDefault(key).getDisplayName();
    }

    /** 口径文本单一取入口: 问答回答、summary 端点、CSV 导出均走这里 */
    public String caliber(String key) {
        return metricOrDefault(key).getCaliber();
    }

    public String formula(String key) {
        return metricOrDefault(key).getFormula();
    }

    public boolean drillSupports(String drill) {
        return drill != null && DRILL.containsKey(drill);
    }

    /** 下钻动作 -> 统计分组（Agent 侧统计分组名） */
    public String drillGroup(String drill) {
        return DRILL.get(drill);
    }

    public boolean compareSupports(String compare) {
        return compare != null && COMPARE.containsKey(compare);
    }

    public String compareCaliber(String compare) {
        return compare == null ? null : COMPARE.get(compare);
    }

    private static Map<String, String[]> catalogTable() {
        String[][] rows = {
                {"amount", "成交额(GMV)",
                 "元;已包含命中活动 discount 折扣,即实际成交金额;不含运费/服务费",
                 "sum(s.amount) over filtered records"},
                {"quantity", "销量",
                 "件;流水 quantity 字段,活动期销量未分折扣折算",
                 "sum(s.quantity) over filtered records"},
                {"records", "流水记录数",
                 "条;命中流水行数,口径=行数非汇总量",
                 "count(s) over filtered records"},
                {"share", "占比",
                 "%;该分组指标 / 全scope 指标（同口径同筛选同区间）",
                 "groupValue / sumTotal"},
                {"daily", "每日趋势",
                 "按 stat_date 逐日桶;区间内缺失无流水的日期视为 0",
                 "group by s.stat_date"},
                {"mom", "环比增长率",
                 "%;（当前区间 - 前同长度相邻区间） / 前同长度相邻区间",
                 "(cur - prev) / prev, prev = 同天数紧邻前窗"},
                {"yoy", "同比增长率",
                 "%;（当前区间 - 去年同日历区间） / 去年同日历区间;闰/未登录期视为 0",
                 "(cur - lastYear) / lastYear, lastYear = 区间 - 1 年"},
        };
        Map<String, String[]> table = new LinkedHashMap<>();
        for (String[] row : rows) {
            table.put(row[0], new String[] {row[1], row[2], row[3]});
        }
        return table;
    }

    private static Map<String, String> drillTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("channel", "channel");
        table.put("product", "product");
        table.put("region", "region");
        table.put("activity", "activity");
        table.put("date", "date");
        return table;
    }

    private static Map<String, String> compareTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("mom", "环比: 前同长度紧邻窗口, 与当前口径可比");
        table.put("yoy", "同比: 去年同日历窗口, 与当前口径可比");
        return table;
    }
}
