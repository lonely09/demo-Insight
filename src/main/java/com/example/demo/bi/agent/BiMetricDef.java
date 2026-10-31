package com.example.demo.bi.agent;

/**
 * 单个指标的完整定义（口径统一的最小单元）。
 * 名称、口径文本、取数公式、单位都从这里取，Agent 回答、接口返回、CSV 导出共用同一份。
 */
public class BiMetricDef {

    private final String key;
    private final String displayName;
    private final String caliber;
    private final String formula;
    private final String unit;

    public BiMetricDef(String key, String displayName, String caliber, String formula) {
        this.key = key;
        this.displayName = displayName;
        this.caliber = caliber;
        this.formula = formula;
        this.unit = switch (key) {
            case "amount" -> "元";
            case "quantity" -> "件";
            case "records" -> "条";
            default -> "";
        };
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCaliber() {
        return caliber;
    }

    public String getFormula() {
        return formula;
    }

    public String getUnit() {
        return unit;
    }
}
