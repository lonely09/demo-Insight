package com.example.demo.bi.agent;

import com.example.demo.bi.domain.BiActivity;
import com.example.demo.bi.domain.BiChannel;
import com.example.demo.bi.domain.BiProduct;
import com.example.demo.bi.domain.BiRegion;
import com.example.demo.bi.repository.BiActivityRepository;
import com.example.demo.bi.repository.BiChannelRepository;
import com.example.demo.bi.repository.BiProductRepository;
import com.example.demo.bi.repository.BiRegionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式中文问数解析器：把"上个月各渠道的成交额"这类业务问法解析成
 * BiQuerySpec（指标 / 时间区间 / 分组维度 / 筛选实体 / 对比 / 占比 / 下钻 / TopN）。
 *
 * 设计取舍：
 *  - 不依赖外部大模型，指标与维度全部来自维表和 BiMetric 登记，解析结果可复现、可审计；
 *  - 实体对齐只做"名称/编码/别名包含"这种确定性匹配，不猜；
 *  - 未识别的时间、维度等使用明确默认值（最近30天、合计），并在 meta 中回传。
 */
@Component
public class BiQueryParser {

    private static final Map<String, List<String>> CHANNEL_ALIASES = Map.of(
            "TMALL", List.of("天猫"),
            "JD", List.of("京东"),
            "DOUYIN", List.of("抖音"),
            "PDD", List.of("拼多多"),
            "WXMP", List.of("微信小程序", "微信"),
            "STORE", List.of("线下门店", "门店", "线下")
    );

    private static final Map<String, List<String>> PRODUCT_ALIASES = Map.of(
            "SKU-1001", List.of("榨汁杯"),
            "SKU-1002", List.of("降噪耳机", "耳机"),
            "SKU-1003", List.of("扫地机器人", "扫地机"),
            "SKU-1004", List.of("保温杯"),
            "SKU-1005", List.of("电动牙刷", "牙刷"),
            "SKU-1006", List.of("面膜"),
            "SKU-1007", List.of("坚果"),
            "SKU-1008", List.of("咖啡"),
            "SKU-1009", List.of("智能手表", "手表"),
            "SKU-1010", List.of("按摩仪", "眼部按摩仪")
    );

    private static final String[] ACTIVITY_TOKENS = {"618", "会员日", "开学季", "国庆", "中秋", "双11", "双十一"};

    private static final Pattern GROUP_MARKER = Pattern.compile(
            "(?:按|各|分|每个|每一个|不同|逐)\\s*(渠道|平台|商品|单品|SKU|sku|品类|类目|分类|区域|大区|地区|省份|城市|活动|促销|日期|天|周|月)");

    private static final Pattern TOP_N = Pattern.compile("(?:前|top|TOP|Top|排名前)\\s*(\\d+)");
    private static final Pattern COUNT_N = Pattern.compile("(\\d+)\\s*(?:个|款|名|条|种)");

    private final BiChannelRepository channelRepository;
    private final BiProductRepository productRepository;
    private final BiRegionRepository regionRepository;
    private final BiActivityRepository activityRepository;
    private final BiDateRangeParser dateRangeParser;

    public BiQueryParser(BiChannelRepository channelRepository,
                         BiProductRepository productRepository,
                         BiRegionRepository regionRepository,
                         BiActivityRepository activityRepository,
                         BiDateRangeParser dateRangeParser) {
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
        this.regionRepository = regionRepository;
        this.activityRepository = activityRepository;
        this.dateRangeParser = dateRangeParser;
    }

    public BiQuerySpec parse(String rawQuestion) {
        if (rawQuestion == null || rawQuestion.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String q = normalize(rawQuestion);
        LocalDate today = LocalDate.now();

        BiQuerySpec spec = new BiQuerySpec();
        spec.setQuestion(rawQuestion.trim());
        spec.setMetricKey(detectMetric(q));

        BiDateRange range = dateRangeParser.parse(q, today);
        spec.setDateFrom(range.from());
        spec.setDateTo(range.to());
        spec.setDateLabel(range.label());

        List<BiChannel> channels = channelRepository.findAll();
        List<BiProduct> products = productRepository.findAll();
        List<BiRegion> regions = regionRepository.findAll();
        List<BiActivity> activities = activityRepository.findAll();

        List<BiChannel> matchedChannels = matchChannels(q, channels);
        List<BiProduct> matchedProducts = matchProducts(q, products);
        List<BiRegion> matchedRegions = matchRegions(q, regions);
        List<BiActivity> matchedActivities = matchActivities(q, activities);

        for (BiChannel c : matchedChannels) {
            spec.getFilters().addChannel(c.getId());
        }
        for (BiProduct p : matchedProducts) {
            spec.getFilters().addProduct(p.getId());
        }
        for (BiRegion r : matchedRegions) {
            spec.getFilters().addRegion(r.getId());
        }
        for (BiActivity a : matchedActivities) {
            spec.getFilters().addActivity(a.getId());
        }
        for (BiProduct p : products) {
            if (p.getCategory() != null && q.contains(p.getCategory())) {
                spec.getFilters().addCategory(p.getCategory());
            }
        }
        if (q.contains("线上") && !q.contains("线下")) {
            spec.getFilters().setChannelType(BiChannel.TYPE_ONLINE);
        } else if (q.contains("线下")) {
            spec.getFilters().setChannelType(BiChannel.TYPE_OFFLINE);
        }

        // 问题里点名活动且没有明确日期时，用活动窗口作为统计区间，避免"国庆期间"被默认30天稀释
        if (!range.explicit() && !matchedActivities.isEmpty()) {
            LocalDate from = matchedActivities.stream().map(BiActivity::getStartDate).min(LocalDate::compareTo).orElse(range.from());
            LocalDate to = matchedActivities.stream().map(BiActivity::getEndDate).max(LocalDate::compareTo).orElse(range.to());
            String names = String.join("、", matchedActivities.stream().map(BiActivity::getName).toList());
            spec.setDateFrom(from);
            spec.setDateTo(to);
            spec.setDateLabel(names + "活动期");
        }

        String group = detectGroup(q, matchedChannels, matchedProducts, matchedRegions);
        spec.setGroupBy(group);
        if ("date".equals(group)) {
            spec.setGrain(detectGrain(q));
        }

        spec.setCompare(detectCompare(q, group));
        spec.setShare(containsAny(q, "占比", "份额", "比例", "贡献度", "百分比"));
        spec.setDrill(containsAny(q, "下钻", "拆解", "细分", "展开", "明细"));
        if (spec.isDrill() && group == null) {
            spec.setGroupBy("region");
            spec.setGrain("day");
        }
        spec.setAscending(containsAny(q, "最低", "最少", "最差", "垫底", "倒数", "升序"));
        spec.setLimit(detectLimit(q, group != null));

        return spec;
    }

    private String detectMetric(String q) {
        if (containsAny(q, "成交额", "销售额", "gmv", "GMV", "营收", "金额", "卖了多少钱", "业绩")) {
            return "amount";
        }
        if (containsAny(q, "销量", "件数", "卖了多少件", "卖了几件", "出货量")) {
            return "quantity";
        }
        if (containsAny(q, "订单", "流水", "记录数", "笔数", "单量")) {
            return "records";
        }
        return "amount";
    }

    private String detectGroup(String q, List<BiChannel> matchedChannels,
                               List<BiProduct> matchedProducts, List<BiRegion> matchedRegions) {
        if (q.contains("商品品类") || q.contains("产品品类") || q.contains("品类结构")) {
            return "category";
        }

        Matcher m = GROUP_MARKER.matcher(q);
        if (m.find()) {
            return mapDimension(m.group(1));
        }

        if (containsAny(q, "趋势", "走势", "逐日", "每天", "每日", "按天", "按日")) {
            return "date";
        }
        if (q.contains("下钻") || q.contains("明细")) {
            return "region";
        }
        if (containsAny(q, "对比", "比较", "分别")) {
            if (matchedChannels.size() >= 2) {
                return "channel";
            }
            if (matchedProducts.size() >= 2) {
                return "product";
            }
            if (matchedRegions.size() >= 2) {
                return "region";
            }
        }
        if (containsAny(q, "占比", "份额", "比例", "排行", "排名", "分布", "哪个", "哪些", "最高", "最低", "最好", "最差")) {
            String dimension = firstDimensionToken(q);
            if (dimension != null) {
                return dimension;
            }
        }
        return null;
    }

    private String firstDimensionToken(String q) {
        String best = null;
        int bestIndex = Integer.MAX_VALUE;
        String[][] tokens = {
                {"渠道", "channel"}, {"平台", "channel"},
                {"商品", "product"}, {"单品", "product"}, {"SKU", "product"}, {"sku", "product"},
                {"品类", "category"}, {"类目", "category"}, {"分类", "category"},
                {"区域", "region"}, {"大区", "region"}, {"地区", "region"}, {"省份", "region"}, {"城市", "region"},
                {"活动", "activity"}, {"促销", "activity"}
        };
        for (String[] token : tokens) {
            int idx = q.indexOf(token[0]);
            if (idx >= 0 && idx < bestIndex) {
                bestIndex = idx;
                best = token[1];
            }
        }
        return best;
    }

    private String mapDimension(String token) {
        return switch (token) {
            case "渠道", "平台" -> "channel";
            case "商品", "单品", "SKU", "sku" -> "product";
            case "品类", "类目", "分类" -> "category";
            case "区域", "大区", "地区", "省份", "城市" -> "region";
            case "活动", "促销" -> "activity";
            case "日期", "天", "周", "月" -> "date";
            default -> null;
        };
    }

    private String detectGrain(String q) {
        if (containsAny(q, "每月", "按月", "逐月", "月度")) {
            return "month";
        }
        if (containsAny(q, "每周", "按周", "逐周", "周度")) {
            return "week";
        }
        return "day";
    }

    private String detectCompare(String q, String group) {
        if (containsAny(q, "同比", "去年同期", "较去年", "比去年")) {
            return "yoy";
        }
        if (containsAny(q, "环比", "上个月同期", "较上月", "比上月", "上月同期")) {
            return "mom";
        }
        if (q.contains("对比") && group == null) {
            return "mom";
        }
        return null;
    }

    private int detectLimit(String q, boolean hasGroup) {
        if (!hasGroup) {
            return 10;
        }
        Matcher top = TOP_N.matcher(q);
        if (top.find()) {
            return clampLimit(Integer.parseInt(top.group(1)));
        }
        Matcher count = COUNT_N.matcher(q);
        if (count.find()) {
            return clampLimit(Integer.parseInt(count.group(1)));
        }
        return 10;
    }

    private int clampLimit(int value) {
        return Math.max(1, Math.min(value, 50));
    }

    private List<BiChannel> matchChannels(String q, List<BiChannel> channels) {
        List<BiChannel> hits = new ArrayList<>();
        for (BiChannel c : channels) {
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(c.getName());
            aliases.add(c.getCode());
            aliases.addAll(CHANNEL_ALIASES.getOrDefault(c.getCode(), List.of()));
            if (aliases.stream().anyMatch(a -> a != null && a.length() >= 2 && q.contains(a.toLowerCase()))) {
                hits.add(c);
            }
        }
        return hits;
    }

    private List<BiProduct> matchProducts(String q, List<BiProduct> products) {
        List<BiProduct> hits = new ArrayList<>();
        for (BiProduct p : products) {
            boolean hit = (p.getName() != null && q.contains(p.getName()))
                    || (p.getSku() != null && q.contains(p.getSku().toLowerCase()));
            if (!hit) {
                hit = PRODUCT_ALIASES.getOrDefault(p.getSku(), List.of()).stream()
                        .anyMatch(alias -> q.contains(alias));
            }
            if (hit) {
                hits.add(p);
            }
        }
        return hits;
    }

    private List<BiRegion> matchRegions(String q, List<BiRegion> regions) {
        List<BiRegion> hits = new ArrayList<>();
        for (BiRegion r : regions) {
            if (r.getName() != null && r.getName().length() >= 2 && q.contains(r.getName())) {
                hits.add(r);
            } else if (r.getCode() != null && q.contains(r.getCode().toLowerCase())) {
                hits.add(r);
            }
        }
        return hits;
    }

    private List<BiActivity> matchActivities(String q, List<BiActivity> activities) {
        List<BiActivity> hits = new ArrayList<>();
        for (BiActivity a : activities) {
            boolean hit = a.getName() != null && q.contains(a.getName());
            if (!hit) {
                for (String token : ACTIVITY_TOKENS) {
                    if (a.getName() != null && a.getName().contains(token) && q.contains(token)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                hits.add(a);
            }
        }
        return hits;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String s) {
        return s.replace('　', ' ').replaceAll("\\s+", "").toLowerCase();
    }
}
