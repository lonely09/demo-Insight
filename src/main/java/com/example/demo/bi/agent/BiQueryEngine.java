package com.example.demo.bi.agent;

import com.example.demo.bi.domain.BiActivity;
import com.example.demo.bi.domain.BiChannel;
import com.example.demo.bi.domain.BiProduct;
import com.example.demo.bi.domain.BiRegion;
import com.example.demo.bi.domain.BiSalesRecord;
import com.example.demo.bi.repository.BiActivityRepository;
import com.example.demo.bi.repository.BiChannelRepository;
import com.example.demo.bi.repository.BiProductRepository;
import com.example.demo.bi.repository.BiRegionRepository;
import com.example.demo.bi.repository.BiSalesRecordRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统计引擎：只认 BiQuerySpec，不解析自然语言。
 * 统一在这里完成筛选、聚合、占比、环比/同比、趋势与区域下钻，
 * 并产出带着口径、权限、traceId、结果指纹的完整结果。
 *
 * 当前数据量（演示级）采用"按日期取明细 + 应用层过滤聚合"，实现简单、口径可复算；
 * 数据量上来后把 query/aggregate 换成 SQL 聚合即可，Spec 与结果结构不变。
 */
@Service
public class BiQueryEngine {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");

    private final BiSalesRecordRepository salesRepository;
    private final BiChannelRepository channelRepository;
    private final BiProductRepository productRepository;
    private final BiRegionRepository regionRepository;
    private final BiActivityRepository activityRepository;
    private final BiMetric metric;
    private final BiPermissionService permissionService;

    public BiQueryEngine(BiSalesRecordRepository salesRepository,
                         BiChannelRepository channelRepository,
                         BiProductRepository productRepository,
                         BiRegionRepository regionRepository,
                         BiActivityRepository activityRepository,
                         BiMetric metric,
                         BiPermissionService permissionService) {
        this.salesRepository = salesRepository;
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
        this.regionRepository = regionRepository;
        this.activityRepository = activityRepository;
        this.metric = metric;
        this.permissionService = permissionService;
    }

    public BiQueryResult execute(BiQuerySpec spec, BiPrincipal principal, String traceId) {
        if (spec.getDateFrom() == null || spec.getDateTo() == null) {
            throw new IllegalArgumentException("统计区间不能为空");
        }

        List<BiChannel> channels = channelRepository.findAll();
        List<BiProduct> products = productRepository.findAll();
        List<BiRegion> regions = regionRepository.findAll();
        List<BiActivity> activities = activityRepository.findAll();

        Map<Long, BiChannel> channelMap = indexChannels(channels);
        Map<Long, BiProduct> productMap = indexProducts(products);
        Map<Long, BiRegion> regionMap = indexRegions(regions);
        Map<Long, BiActivity> activityMap = indexActivities(activities);

        Set<Long> regionIds = permissionService.effectiveRegionIds(
                principal, spec.getFilters().getRegionIds(), regions);

        Set<Long> channelIds = new LinkedHashSet<>(spec.getFilters().getChannelIds());
        if (spec.getFilters().getChannelType() != null) {
            Set<Long> typed = new LinkedHashSet<>();
            for (BiChannel c : channels) {
                if (spec.getFilters().getChannelType().equals(c.getType())) {
                    typed.add(c.getId());
                }
            }
            if (channelIds.isEmpty()) {
                channelIds.addAll(typed);
            } else {
                channelIds.retainAll(typed);
            }
        }

        Set<Long> productIds = new LinkedHashSet<>(spec.getFilters().getProductIds());
        if (!spec.getFilters().getCategories().isEmpty()) {
            Set<Long> ofCategory = new LinkedHashSet<>();
            for (BiProduct p : products) {
                if (spec.getFilters().getCategories().contains(p.getCategory())) {
                    ofCategory.add(p.getId());
                }
            }
            if (productIds.isEmpty()) {
                productIds.addAll(ofCategory);
            } else {
                productIds.retainAll(ofCategory);
            }
        }

        Set<Long> activityIds = new LinkedHashSet<>(spec.getFilters().getActivityIds());

        List<BiSalesRecord> records = query(spec.getDateFrom(), spec.getDateTo(),
                regionIds, channelIds, productIds, activityIds);
        Analysis current = analyze(records, spec, channelMap, productMap, regionMap, activityMap);

        BiMetricDef metricDef = metric.metricOrDefault(spec.getMetricKey());
        BigDecimal totalMetric = metricValue(current.amount, current.quantity, current.records, spec.getMetricKey());

        List<BiQueryResult.GroupRow> groups = buildGroups(current, spec, totalMetric);
        List<BiQueryResult.TrendPoint> trend = buildTrend(current, spec);
        BiQueryResult.Comparison comparison = buildComparison(spec, principal,
                regionIds, channelIds, productIds, activityIds,
                channelMap, productMap, regionMap, activityMap, current, totalMetric);
        List<BiQueryResult.DrillNode> drill = spec.isDrill() && "region".equals(spec.getGroupBy())
                ? buildDrill(records, spec, principal, regionMap, totalMetric)
                : List.of();

        List<BiQueryResult.AppliedFilter> appliedFilters = buildAppliedFilters(
                spec, principal, channelIds, productIds, regionIds, activityIds,
                channelMap, productMap, regionMap, activityMap);

        BiQueryResult.Totals totals = new BiQueryResult.Totals(
                scale(current.amount), current.quantity, current.records);

        String hash = resultHash(spec, totals, groups);
        BiQueryResult.PermissionView permissionView = new BiQueryResult.PermissionView(
                principal.userId(), principal.role(), principal.roleLabel(), principal.scopeLabel(),
                scopeRegionNames(principal, regionMap), principal.exportAllowed());
        BiQueryResult.Meta meta = new BiQueryResult.Meta(
                traceId, spec.getMetricKey(), metricDef.getDisplayName(), metricDef.getUnit(),
                metricDef.getCaliber(), metricDef.getFormula(),
                spec.getDateFrom(), spec.getDateTo(), spec.getDateLabel(),
                spec.getGroupBy(), spec.groupLabel(), spec.getCompare(), spec.isShare(), spec.isDrill(),
                appliedFilters, records.size(), hash, LocalDateTime.now(),
                "bi_sales_record 明细聚合；指标/口径由 BiMetric 统一登记，解析规格随 traceId 落审计",
                permissionView);

        BiQueryResult result = new BiQueryResult();
        result.setTotals(totals);
        result.setGroups(groups);
        result.setTrend(trend);
        result.setComparison(comparison);
        result.setDrill(drill);
        result.setMeta(meta);
        return result;
    }

    // ================= 取数与过滤 =================

    private List<BiSalesRecord> query(LocalDate from, LocalDate to,
                                      Set<Long> regionIds, Set<Long> channelIds,
                                      Set<Long> productIds, Set<Long> activityIds) {
        List<BiSalesRecord> all = salesRepository.findByStatDateBetween(from, to);
        List<BiSalesRecord> filtered = new ArrayList<>(all.size());
        for (BiSalesRecord r : all) {
            if (!containsOrEmpty(regionIds, r.getRegionId())) {
                continue;
            }
            if (!containsOrEmpty(channelIds, r.getChannelId())) {
                continue;
            }
            if (!containsOrEmpty(productIds, r.getProductId())) {
                continue;
            }
            if (!containsOrEmpty(activityIds, r.getActivityId())) {
                continue;
            }
            filtered.add(r);
        }
        return filtered;
    }

    private boolean containsOrEmpty(Set<Long> ids, Long value) {
        return ids == null || ids.isEmpty() || (value != null && ids.contains(value));
    }

    // ================= 聚合 =================

    private Analysis analyze(List<BiSalesRecord> records, BiQuerySpec spec,
                             Map<Long, BiChannel> channelMap,
                             Map<Long, BiProduct> productMap,
                             Map<Long, BiRegion> regionMap,
                             Map<Long, BiActivity> activityMap) {
        Analysis analysis = new Analysis();
        String group = spec.getGroupBy();
        for (BiSalesRecord r : records) {
            analysis.amount = analysis.amount.add(amountOf(r));
            analysis.quantity += r.getQuantity();
            analysis.records++;

            if (group == null) {
                continue;
            }
            String key = keyOf(r, group, spec.getGrain(), productMap);
            Bucket bucket = analysis.buckets.computeIfAbsent(key,
                    k -> new Bucket(k, nameOf(r, group, spec.getGrain(), channelMap, productMap, regionMap, activityMap),
                            idOf(r, group)));
            bucket.amount = bucket.amount.add(amountOf(r));
            bucket.quantity += r.getQuantity();
            bucket.records++;
        }
        return analysis;
    }

    private String keyOf(BiSalesRecord r, String group, String grain, Map<Long, BiProduct> productMap) {
        return switch (group) {
            case "channel" -> String.valueOf(r.getChannelId());
            case "product" -> String.valueOf(r.getProductId());
            case "category" -> categoryOf(r, productMap);
            case "region" -> String.valueOf(r.getRegionId());
            case "activity" -> String.valueOf(r.getActivityId() == null ? 0L : r.getActivityId());
            case "date" -> bucketDate(r.getStatDate(), grain).toString();
            default -> "all";
        };
    }

    private String categoryOf(BiSalesRecord r, Map<Long, BiProduct> productMap) {
        BiProduct p = productMap.get(r.getProductId());
        return p == null || p.getCategory() == null ? "未分类" : p.getCategory();
    }

    private String nameOf(BiSalesRecord r, String group, String grain,
                          Map<Long, BiChannel> channelMap,
                          Map<Long, BiProduct> productMap,
                          Map<Long, BiRegion> regionMap,
                          Map<Long, BiActivity> activityMap) {
        return switch (group) {
            case "channel" -> channelMap.containsKey(r.getChannelId())
                    ? channelMap.get(r.getChannelId()).getName() : "未知渠道";
            case "product" -> productMap.containsKey(r.getProductId())
                    ? productMap.get(r.getProductId()).getName() : "未知商品";
            case "category" -> categoryOf(r, productMap);
            case "region" -> regionMap.containsKey(r.getRegionId())
                    ? regionMap.get(r.getRegionId()).getName() : "未知区域";
            case "activity" -> r.getActivityId() == null ? "非活动"
                    : activityMap.containsKey(r.getActivityId())
                    ? activityMap.get(r.getActivityId()).getName() : "未知活动";
            case "date" -> bucketLabel(bucketDate(r.getStatDate(), grain), grain);
            default -> "合计";
        };
    }

    private Long idOf(BiSalesRecord r, String group) {
        return switch (group) {
            case "channel" -> r.getChannelId();
            case "product" -> r.getProductId();
            case "region" -> r.getRegionId();
            case "activity" -> r.getActivityId();
            default -> null;
        };
    }

    private List<BiQueryResult.GroupRow> buildGroups(Analysis analysis, BiQuerySpec spec, BigDecimal totalMetric) {
        if (spec.getGroupBy() == null) {
            return List.of();
        }
        List<Bucket> buckets = new ArrayList<>(analysis.buckets.values());
        if ("date".equals(spec.getGroupBy())) {
            buckets.sort(Comparator.comparing(b -> b.key));
        } else {
            buckets.sort((a, b) -> {
                int cmp = metricValue(b, spec.getMetricKey()).compareTo(metricValue(a, spec.getMetricKey()));
                if (cmp == 0) {
                    cmp = a.name.compareTo(b.name);
                }
                return spec.isAscending() ? -cmp : cmp;
            });
            if (buckets.size() > spec.getLimit()) {
                buckets = buckets.subList(0, spec.getLimit());
            }
        }

        List<BiQueryResult.GroupRow> rows = new ArrayList<>(buckets.size());
        int rank = 1;
        for (Bucket bucket : buckets) {
            rows.add(new BiQueryResult.GroupRow(rank++, bucket.key, bucket.name,
                    scale(bucket.amount), bucket.quantity, bucket.records,
                    share(bucket.amount, bucket.quantity, bucket.records, totalMetric, spec.getMetricKey())));
        }
        return rows;
    }

    private List<BiQueryResult.TrendPoint> buildTrend(Analysis analysis, BiQuerySpec spec) {
        if (!"date".equals(spec.getGroupBy())) {
            return List.of();
        }
        String grain = spec.getGrain();
        LocalDate cursor = bucketDate(spec.getDateFrom(), grain);
        LocalDate end = bucketDate(spec.getDateTo(), grain);
        List<BiQueryResult.TrendPoint> points = new ArrayList<>();
        int guard = 0;
        while (!cursor.isAfter(end) && guard++ < 1000) {
            Bucket bucket = analysis.buckets.get(cursor.toString());
            points.add(new BiQueryResult.TrendPoint(
                    bucketLabel(cursor, grain), cursor,
                    bucket == null ? BigDecimal.ZERO : scale(bucket.amount),
                    bucket == null ? 0L : bucket.quantity,
                    bucket == null ? 0L : bucket.records));
            cursor = advance(cursor, grain);
        }
        return points;
    }

    private BiQueryResult.Comparison buildComparison(BiQuerySpec spec, BiPrincipal principal,
                                                     Set<Long> regionIds, Set<Long> channelIds,
                                                     Set<Long> productIds, Set<Long> activityIds,
                                                     Map<Long, BiChannel> channelMap,
                                                     Map<Long, BiProduct> productMap,
                                                     Map<Long, BiRegion> regionMap,
                                                     Map<Long, BiActivity> activityMap,
                                                     Analysis current, BigDecimal currentMetric) {
        if (spec.getCompare() == null) {
            return null;
        }

        LocalDate priorFrom;
        LocalDate priorTo;
        String label;
        if ("yoy".equals(spec.getCompare())) {
            priorFrom = spec.getDateFrom().minusYears(1);
            priorTo = spec.getDateTo().minusYears(1);
            label = "同比（去年同日历区间）";
        } else {
            long days = ChronoUnit.DAYS.between(spec.getDateFrom(), spec.getDateTo()) + 1;
            priorTo = spec.getDateFrom().minusDays(1);
            priorFrom = priorTo.minusDays(days - 1);
            label = "环比（前 " + days + " 天）";
        }

        List<BiSalesRecord> priorRecords = query(priorFrom, priorTo,
                regionIds, channelIds, productIds, activityIds);
        Analysis prior = analyze(priorRecords, spec, channelMap, productMap, regionMap, activityMap);
        BigDecimal priorMetric = metricValue(prior.amount, prior.quantity, prior.records, spec.getMetricKey());

        BigDecimal growth = null;
        String note;
        if (priorRecords.isEmpty()) {
            note = "同期无流水，当前数据窗口可能未覆盖该区间";
        } else if (priorMetric.signum() == 0) {
            note = "同期指标为 0，增长率不可计算";
        } else {
            growth = currentMetric.subtract(priorMetric)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(priorMetric, 2, RoundingMode.HALF_UP);
            note = "口径一致：同一指标、同一筛选、同一区域权限范围";
        }

        List<BiQueryResult.GroupGrowth> growths = new ArrayList<>();
        for (Bucket bucket : current.buckets.values()) {
            Bucket before = prior.buckets.get(bucket.key);
            BigDecimal beforeValue = before == null ? BigDecimal.ZERO
                    : metricValue(before, spec.getMetricKey());
            BigDecimal growthRate = beforeValue.signum() == 0 ? null
                    : metricValue(bucket, spec.getMetricKey()).subtract(beforeValue)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(beforeValue, 2, RoundingMode.HALF_UP);
            growths.add(new BiQueryResult.GroupGrowth(bucket.name,
                    metricValue(bucket, spec.getMetricKey()), beforeValue, growthRate));
        }
        growths.sort((a, b) -> b.current().compareTo(a.current()));
        if (growths.size() > 20) {
            growths = growths.subList(0, 20);
        }

        return new BiQueryResult.Comparison(spec.getCompare(), label, priorFrom, priorTo,
                scale(currentMetric), scale(priorMetric), growth, note, growths);
    }

    // ================= 区域下钻 =================

    private List<BiQueryResult.DrillNode> buildDrill(List<BiSalesRecord> records, BiQuerySpec spec,
                                                     BiPrincipal principal,
                                                     Map<Long, BiRegion> regionMap,
                                                     BigDecimal totalMetric) {
        Map<Long, Agg> direct = new HashMap<>();
        for (BiSalesRecord r : records) {
            Agg agg = direct.computeIfAbsent(r.getRegionId(), k -> new Agg());
            agg.amount = agg.amount.add(amountOf(r));
            agg.quantity += r.getQuantity();
            agg.records++;
        }

        Map<Long, List<BiRegion>> children = new HashMap<>();
        for (BiRegion region : regionMap.values()) {
            if (region.getParentId() != null) {
                children.computeIfAbsent(region.getParentId(), k -> new ArrayList<>()).add(region);
            }
        }

        List<BiRegion> roots = new ArrayList<>();
        if (principal.scopeRegionId() != null && regionMap.containsKey(principal.scopeRegionId())) {
            roots.add(regionMap.get(principal.scopeRegionId()));
        } else {
            for (BiRegion region : regionMap.values()) {
                if (region.getParentId() == null) {
                    roots.add(region);
                }
            }
        }

        List<BiQueryResult.DrillNode> nodes = new ArrayList<>();
        for (BiRegion root : roots) {
            BiQueryResult.DrillNode node = buildDrillNode(root, direct, children,
                    principal.allowedRegionIds(), totalMetric, spec.getMetricKey());
            if (node != null) {
                nodes.add(node);
            }
        }
        nodes.sort((a, b) -> compareMetric(b, a, spec.getMetricKey()));
        return nodes;
    }

    private BiQueryResult.DrillNode buildDrillNode(BiRegion region, Map<Long, Agg> direct,
                                                   Map<Long, List<BiRegion>> children,
                                                   Set<Long> allowed, BigDecimal totalMetric,
                                                   String metricKey) {
        if (allowed != null && !allowed.contains(region.getId())) {
            return null;
        }
        List<BiQueryResult.DrillNode> childNodes = new ArrayList<>();
        for (BiRegion child : children.getOrDefault(region.getId(), List.of())) {
            BiQueryResult.DrillNode node = buildDrillNode(child, direct, children, allowed, totalMetric, metricKey);
            if (node != null) {
                childNodes.add(node);
            }
        }
        childNodes.sort((a, b) -> compareMetric(b, a, metricKey));

        Agg own = direct.get(region.getId());
        BigDecimal amount = own == null ? BigDecimal.ZERO : own.amount;
        long quantity = own == null ? 0 : own.quantity;
        long records = own == null ? 0 : own.records;
        for (BiQueryResult.DrillNode child : childNodes) {
            amount = amount.add(child.amount());
            quantity += child.quantity();
            records += child.records();
        }
        if (records == 0) {
            return null;
        }
        BigDecimal share = share(amount, quantity, records, totalMetric, metricKey);
        return new BiQueryResult.DrillNode(region.getId(), region.getName(), scale(amount),
                quantity, records, share, childNodes);
    }

    // ================= 元信息 =================

    private List<BiQueryResult.AppliedFilter> buildAppliedFilters(BiQuerySpec spec, BiPrincipal principal,
                                                                  Set<Long> channelIds, Set<Long> productIds,
                                                                  Set<Long> regionIds, Set<Long> activityIds,
                                                                  Map<Long, BiChannel> channelMap,
                                                                  Map<Long, BiProduct> productMap,
                                                                  Map<Long, BiRegion> regionMap,
                                                                  Map<Long, BiActivity> activityMap) {
        List<BiQueryResult.AppliedFilter> filters = new ArrayList<>();
        addFilter(filters, "channel", "渠道", names(channelIds, channelMap));
        addFilter(filters, "product", "商品", names(productIds, productMap));
        addFilter(filters, "category", "品类", new ArrayList<>(spec.getFilters().getCategories()));
        addFilter(filters, "region", "区域", names(regionIds, regionMap));
        addFilter(filters, "activity", "活动", names(activityIds, activityMap));
        if (spec.getFilters().getChannelType() != null) {
            filters.add(new BiQueryResult.AppliedFilter("channelType", "渠道类型",
                    List.of("ONLINE".equals(spec.getFilters().getChannelType()) ? "线上" : "线下")));
        }
        if (!principal.unrestricted()) {
            filters.add(new BiQueryResult.AppliedFilter("regionScope", "权限区域范围",
                    List.of(principal.scopeLabel())));
        }
        return filters;
    }

    private void addFilter(List<BiQueryResult.AppliedFilter> filters, String field, String label,
                           List<String> values) {
        if (values != null && !values.isEmpty()) {
            filters.add(new BiQueryResult.AppliedFilter(field, label, values));
        }
    }

    private <T> List<String> names(Set<Long> ids, Map<Long, T> map) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Long id : ids) {
            T item = map.get(id);
            if (item instanceof BiChannel c) {
                values.add(c.getName());
            } else if (item instanceof BiProduct p) {
                values.add(p.getName());
            } else if (item instanceof BiRegion r) {
                values.add(r.getName());
            } else if (item instanceof BiActivity a) {
                values.add(a.getName());
            }
        }
        return values;
    }

    private List<String> scopeRegionNames(BiPrincipal principal, Map<Long, BiRegion> regionMap) {
        if (principal.allowedRegionIds() == null) {
            return List.of("全部区域");
        }
        List<String> names = new ArrayList<>();
        for (Long id : principal.allowedRegionIds()) {
            BiRegion region = regionMap.get(id);
            names.add(region == null ? String.valueOf(id) : region.getName());
        }
        return names;
    }

    private String resultHash(BiQuerySpec spec, BiQueryResult.Totals totals,
                              List<BiQueryResult.GroupRow> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append(spec.getMetricKey()).append('|')
                .append(spec.getDateFrom()).append('|').append(spec.getDateTo()).append('|')
                .append(spec.getGroupBy()).append('|').append(spec.getCompare()).append('|')
                .append("total=").append(plain(totals.amount())).append(',')
                .append(totals.quantity()).append(',').append(totals.records());
        for (BiQueryResult.GroupRow row : groups) {
            sb.append("|row=").append(row.name()).append(',')
                    .append(plain(row.amount())).append(',').append(row.quantity())
                    .append(',').append(row.records()).append(',').append(plain(row.share()));
        }
        return BiHash.sha256(sb.toString());
    }

    // ================= 工具 =================

    private BigDecimal metricValue(Analysis analysis, String metricKey) {
        return metricValue(analysis.amount, analysis.quantity, analysis.records, metricKey);
    }

    private BigDecimal metricValue(Bucket bucket, String metricKey) {
        return metricValue(bucket.amount, bucket.quantity, bucket.records, metricKey);
    }

    private BigDecimal metricValue(BigDecimal amount, long quantity, long records, String metricKey) {
        return switch (metricKey == null ? "amount" : metricKey) {
            case "quantity" -> BigDecimal.valueOf(quantity);
            case "records" -> BigDecimal.valueOf(records);
            default -> amount == null ? BigDecimal.ZERO : amount;
        };
    }

    private BigDecimal metricValue(BiQueryResult.DrillNode node, String metricKey) {
        return metricValue(node.amount(), node.quantity(), node.records(), metricKey);
    }

    private int compareMetric(BiQueryResult.DrillNode a, BiQueryResult.DrillNode b, String metricKey) {
        return metricValue(a, metricKey).compareTo(metricValue(b, metricKey));
    }

    private BigDecimal share(BigDecimal amount, long quantity, long records,
                             BigDecimal totalMetric, String metricKey) {
        if (totalMetric == null || totalMetric.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal value = metricValue(amount, quantity, records, metricKey);
        return value.multiply(BigDecimal.valueOf(100))
                .divide(totalMetric, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal amountOf(BiSalesRecord record) {
        return record.getAmount() == null ? BigDecimal.ZERO : record.getAmount();
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private LocalDate bucketDate(LocalDate date, String grain) {
        return switch (grain == null ? "day" : grain) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private LocalDate advance(LocalDate date, String grain) {
        return switch (grain == null ? "day" : grain) {
            case "week" -> date.plusWeeks(1);
            case "month" -> date.plusMonths(1);
            default -> date.plusDays(1);
        };
    }

    private String bucketLabel(LocalDate date, String grain) {
        return "month".equals(grain) ? date.format(MONTH_LABEL) : date.format(DAY_LABEL);
    }

    private Map<Long, BiChannel> indexChannels(List<BiChannel> channels) {
        Map<Long, BiChannel> map = new LinkedHashMap<>();
        for (BiChannel c : channels) {
            map.put(c.getId(), c);
        }
        return map;
    }

    private Map<Long, BiProduct> indexProducts(List<BiProduct> products) {
        Map<Long, BiProduct> map = new LinkedHashMap<>();
        for (BiProduct p : products) {
            map.put(p.getId(), p);
        }
        return map;
    }

    private Map<Long, BiRegion> indexRegions(List<BiRegion> regions) {
        Map<Long, BiRegion> map = new LinkedHashMap<>();
        for (BiRegion r : regions) {
            map.put(r.getId(), r);
        }
        return map;
    }

    private Map<Long, BiActivity> indexActivities(List<BiActivity> activities) {
        Map<Long, BiActivity> map = new LinkedHashMap<>();
        for (BiActivity a : activities) {
            map.put(a.getId(), a);
        }
        return map;
    }

    private static class Agg {
        BigDecimal amount = BigDecimal.ZERO;
        long quantity;
        long records;
    }

    private static class Bucket extends Agg {
        final String key;
        final String name;
        final Long id;

        Bucket(String key, String name, Long id) {
            this.key = key;
            this.name = name;
            this.id = id;
        }
    }

    private static class Analysis extends Agg {
        final Map<String, Bucket> buckets = new LinkedHashMap<>();
    }
}
