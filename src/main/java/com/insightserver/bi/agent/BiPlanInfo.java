package com.insightserver.bi.agent;

/**
 * 本次查询的规划来源，写入结果 meta 与审计。
 * planner: LLM / RULE / RULE_FALLBACK
 */
public record BiPlanInfo(String planner, String model, String note, String rawOutput) {

    public static BiPlanInfo rule() {
        return new BiPlanInfo("RULE", null, null, null);
    }

    public boolean llmUsed() {
        return "LLM".equals(planner);
    }

    public String label() {
        return switch (planner == null ? "RULE" : planner) {
            case "LLM" -> "大模型规划";
            case "RULE_FALLBACK" -> "规则回退";
            default -> "规则解析";
        };
    }
}
