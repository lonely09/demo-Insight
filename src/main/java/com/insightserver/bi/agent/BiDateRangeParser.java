package com.insightserver.bi.agent;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文统计区间解析：把"上个月""最近7天""9月1日到9月15日""去年Q3"等说法
 * 统一成 [from, to] 闭区间。默认区间为最近 30 天（含今天）。
 */
@Component
public class BiDateRangeParser {

    private static final Pattern FULL_RANGE = Pattern.compile(
            "(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?\\s*"
                    + "(?:到|至|~|～|—)\\s*"
                    + "(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?");

    private static final Pattern FULL_DATE = Pattern.compile(
            "(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?");

    private static final Pattern YEAR_MONTH_DAY = Pattern.compile(
            "(?:(去年|今年|前年)\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");

    private static final Pattern YEAR_MONTH = Pattern.compile(
            "(?:(去年|今年|前年)\\s*)?(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");

    private static final Pattern MONTH_ONLY = Pattern.compile(
            "(?:(去年|今年|前年)\\s*)?(\\d{1,2})\\s*月(?:份)?");

    private static final Pattern YEAR_ONLY = Pattern.compile("(\\d{4})\\s*年");

    private static final Pattern RELATIVE = Pattern.compile(
            "(?:最近|近|过去|前)\\s*([0-9一二两三四五六七八九十]+)\\s*个?\\s*(天|日|周|星期|礼拜|月|季度|年)");

    private static final Pattern QUARTER_CN = Pattern.compile(
            "(?:(去年|今年|前年)\\s*)?第?\\s*([1-4一二三四])\\s*季度");

    private static final Pattern QUARTER_EN = Pattern.compile(
            "(?:(去年|今年|前年)\\s*)?[Qq]\\s*([1-4])");

    public BiDateRange parse(String rawQuestion, LocalDate today) {
        String q = normalize(rawQuestion);

        Matcher full = FULL_RANGE.matcher(q);
        if (full.find()) {
            LocalDate from = date(full.group(1), full.group(2), full.group(3));
            LocalDate to = date(full.group(4), full.group(5), full.group(6));
            return BiDateRange.of(rangeLabel(from, to), from, to);
        }

        // 两段式区间：9月1日到9月15日、9月到10月
        for (String sep : new String[]{"到", "至", "~", "～", "—"}) {
            int idx = q.indexOf(sep);
            if (idx > 0 && idx < q.length() - 1) {
                BiDateRange left = parseSegment(q.substring(0, idx), today);
                BiDateRange right = parseSegment(q.substring(idx + 1), today);
                if (left != null && right != null) {
                    return BiDateRange.of(rangeLabel(left.from(), right.to()), left.from(), right.to());
                }
            }
        }

        BiDateRange explicit = parseSegment(q, today);
        if (explicit != null) {
            return explicit;
        }

        Matcher year = YEAR_ONLY.matcher(q);
        if (year.find()) {
            int y = Integer.parseInt(year.group(1));
            LocalDate from = LocalDate.of(y, 1, 1);
            LocalDate to = y == today.getYear() ? today : LocalDate.of(y, 12, 31);
            return BiDateRange.of(y + "年", from, to);
        }

        BiDateRange relative = parseRelative(q, today);
        if (relative != null) {
            return relative;
        }

        return new BiDateRange("最近30天", today.minusDays(29), today, false);
    }

    /** 解析"某一天/某一月"这一侧；用于区间两端 */
    private BiDateRange parseSegment(String text, LocalDate today) {
        Matcher m = FULL_DATE.matcher(text);
        if (m.find()) {
            LocalDate d = date(m.group(1), m.group(2), m.group(3));
            return BiDateRange.of(rangeLabel(d, d), d, d);
        }

        m = YEAR_MONTH.matcher(text);
        if (m.find()) {
            int y = Integer.parseInt(m.group(2));
            int mo = Integer.parseInt(m.group(3));
            return monthRange(y, mo, today);
        }

        m = YEAR_MONTH_DAY.matcher(text);
        if (m.find()) {
            int y = yearWithOffset(m.group(1), today);
            int mo = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            LocalDate d = LocalDate.of(y, mo, Math.min(day, YearMonth.of(y, mo).lengthOfMonth()));
            return BiDateRange.of(rangeLabel(d, d), d, d);
        }

        m = MONTH_ONLY.matcher(text);
        if (m.find()) {
            int y = yearWithOffset(m.group(1), today);
            int mo = Integer.parseInt(m.group(2));
            if (mo >= 1 && mo <= 12) {
                return monthRange(y, mo, today);
            }
        }
        return null;
    }

    private BiDateRange parseRelative(String q, LocalDate today) {
        if (q.contains("今天") || q.contains("今日")) {
            return BiDateRange.of("今天", today, today);
        }
        if (q.contains("昨天") || q.contains("昨日")) {
            LocalDate d = today.minusDays(1);
            return BiDateRange.of("昨天", d, d);
        }
        if (q.contains("前天")) {
            LocalDate d = today.minusDays(2);
            return BiDateRange.of("前天", d, d);
        }
        if (q.contains("上上周")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(2);
            return BiDateRange.of("上上周", monday, monday.plusDays(6));
        }
        if (q.contains("上周") || q.contains("上个星期")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            return BiDateRange.of("上周", monday, monday.plusDays(6));
        }
        if (q.contains("本周") || q.contains("这周") || q.contains("这个星期")) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return BiDateRange.of("本周", monday, today);
        }
        if (q.contains("上上个月")) {
            YearMonth ym = YearMonth.from(today).minusMonths(2);
            return BiDateRange.of("上上个月", ym.atDay(1), ym.atEndOfMonth());
        }
        if (q.contains("上个月") || q.contains("上月")) {
            YearMonth ym = YearMonth.from(today).minusMonths(1);
            return BiDateRange.of("上个月", ym.atDay(1), ym.atEndOfMonth());
        }
        if (q.contains("本月") || q.contains("这个月") || q.contains("当月")) {
            return BiDateRange.of("本月", today.withDayOfMonth(1), today);
        }
        if (q.contains("上季度") || q.contains("上个季度")) {
            LocalDate start = quarterStart(today.minusMonths(3));
            LocalDate end = quarterStart(today).minusDays(1);
            return BiDateRange.of("上季度", start, end);
        }
        if (q.contains("本季度") || q.contains("这个季度") || q.contains("当季")) {
            return BiDateRange.of("本季度", quarterStart(today), today);
        }
        Matcher quarter = QUARTER_CN.matcher(q);
        if (!quarter.find()) {
            quarter = QUARTER_EN.matcher(q);
            if (quarter.find()) {
                int y = yearWithOffset(quarter.group(1), today);
                int n = Integer.parseInt(quarter.group(2));
                return quarterRange(y, n, today);
            }
        } else {
            int y = yearWithOffset(quarter.group(1), today);
            int n = chineseNumber(quarter.group(2));
            return quarterRange(y, n, today);
        }

        if (q.contains("去年")) {
            int y = today.getYear() - 1;
            return BiDateRange.of("去年", LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
        }
        if (q.contains("今年") || q.contains("本年")) {
            return BiDateRange.of("今年", LocalDate.of(today.getYear(), 1, 1), today);
        }
        if (q.contains("前年")) {
            int y = today.getYear() - 2;
            return BiDateRange.of("前年", LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
        }

        if (q.contains("近半年") || q.contains("最近半年")) {
            return BiDateRange.of("近半年", today.minusMonths(6).plusDays(1), today);
        }
        if (q.contains("近一年") || q.contains("最近一年")) {
            return BiDateRange.of("近一年", today.minusYears(1).plusDays(1), today);
        }

        Matcher rel = RELATIVE.matcher(q);
        if (rel.find()) {
            int n = parseCount(rel.group(1));
            String unit = rel.group(2);
            if (n <= 0) {
                n = 7;
            }
            if (n > 3660) {
                n = 3660;
            }
            return switch (unit) {
                case "天", "日" -> BiDateRange.of("最近" + n + "天", today.minusDays(n - 1L), today);
                case "周", "星期", "礼拜" -> BiDateRange.of("最近" + n + "周", today.minusDays(n * 7L - 1), today);
                case "月" -> BiDateRange.of("最近" + n + "个月", today.minusMonths(n).plusDays(1), today);
                case "季度" -> BiDateRange.of("最近" + n + "个季度", today.minusMonths(n * 3L).plusDays(1), today);
                case "年" -> BiDateRange.of("最近" + n + "年", today.minusYears(n).plusDays(1), today);
                default -> null;
            };
        }
        return null;
    }

    private BiDateRange monthRange(int year, int month, LocalDate today) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        if (year == today.getYear() && month == today.getMonthValue()) {
            to = today;
        }
        return BiDateRange.of(year + "年" + month + "月", from, to);
    }

    private LocalDate quarterStart(LocalDate d) {
        int month = ((d.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(d.getYear(), month, 1);
    }

    private BiDateRange quarterRange(int year, int quarter, LocalDate today) {
        LocalDate start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        if (year == today.getYear() && !end.isBefore(today)) {
            end = today;
        }
        return BiDateRange.of(year + "年Q" + quarter, start, end);
    }

    private int yearWithOffset(String offset, LocalDate today) {
        if (offset == null) {
            return today.getYear();
        }
        return switch (offset) {
            case "去年" -> today.getYear() - 1;
            case "前年" -> today.getYear() - 2;
            default -> today.getYear();
        };
    }

    private LocalDate date(String y, String m, String d) {
        int year = Integer.parseInt(y);
        int month = Integer.parseInt(m);
        int day = Integer.parseInt(d);
        return LocalDate.of(year, month, Math.min(day, YearMonth.of(year, month).lengthOfMonth()));
    }

    private String rangeLabel(LocalDate from, LocalDate to) {
        if (from.equals(to)) {
            return from.toString();
        }
        return from + " 至 " + to;
    }

    private String normalize(String s) {
        return s == null ? "" : s.replace('　', ' ').replaceAll("\\s+", "");
    }

    private int parseCount(String s) {
        if (s.matches("\\d+")) {
            return Integer.parseInt(s);
        }
        return chineseNumber(s);
    }

    private int chineseNumber(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        if (s.matches("[1-4]")) {
            return Integer.parseInt(s);
        }
        if ("两".equals(s)) {
            return 2;
        }
        String digits = "零一二三四五六七八九";
        if (s.contains("十")) {
            String[] parts = s.split("十", -1);
            int tens = parts[0].isBlank() ? 1 : digits.indexOf(parts[0].charAt(0));
            int ones = parts.length > 1 && !parts[1].isBlank() ? digits.indexOf(parts[1].charAt(0)) : 0;
            return tens * 10 + ones;
        }
        return s.length() == 1 ? digits.indexOf(s.charAt(0)) : 0;
    }
}
