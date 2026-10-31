package com.example.demo.bi.agent;

import java.time.LocalDate;

/**
 * 自然语言解析出的统计区间。
 * explicit=true 表示用户在问题里明确给了日期，活动窗口覆盖等逻辑只对非明确区间生效。
 */
public record BiDateRange(String label, LocalDate from, LocalDate to, boolean explicit) {

    public BiDateRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("统计区间不能为空");
        }
        if (to.isBefore(from)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
    }

    public static BiDateRange of(String label, LocalDate from, LocalDate to) {
        return new BiDateRange(label, from, to, true);
    }
}
