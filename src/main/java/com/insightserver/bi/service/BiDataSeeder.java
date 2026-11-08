package com.insightserver.bi.service;

import com.insightserver.bi.domain.*;
import com.insightserver.bi.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 假数据生成器：为对话式数据统计 Agent 造一组可演示的业务数据。
 * 数据特点（方便后续统计演示：对比、下钻、趋势）：
 *  - 区域：华东最强、华南次之；
 *  - 渠道：抖音随时间增长，线下稳定；
 *  - 周末销量上浮；
 *  - 活动窗口内销量上浮并打折扣。
 * 日期相对"今天"滚动，保证任何时候造出的数据都新鲜；
 * 固定随机种子保证同一天多次生成结果一致。
 */
@Component
public class BiDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BiDataSeeder.class);
    private static final long SEED = 20261030L;

    private final BiRegionRepository regionRepository;
    private final BiChannelRepository channelRepository;
    private final BiProductRepository productRepository;
    private final BiActivityRepository activityRepository;
    private final BiSalesRecordRepository salesRepository;

    @Value("${bi.seed-on-startup:true}")
    private boolean seedOnStartup;

    @Value("${bi.seed-days:120}")
    private int seedDays;

    public BiDataSeeder(BiRegionRepository regionRepository,
                        BiChannelRepository channelRepository,
                        BiProductRepository productRepository,
                        BiActivityRepository activityRepository,
                        BiSalesRecordRepository salesRepository) {
        this.regionRepository = regionRepository;
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
        this.activityRepository = activityRepository;
        this.salesRepository = salesRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedOnStartup) {
            seed(false);
        }
    }

    /** 生成假数据；force=true 时先清空再重造 */
    @Transactional
    public Map<String, Object> seed(boolean force) {
        if (force) {
            salesRepository.deleteAllInBatch();
            activityRepository.deleteAllInBatch();
            productRepository.deleteAllInBatch();
            channelRepository.deleteAllInBatch();
            regionRepository.deleteAllInBatch();
        } else if (salesRepository.count() > 0) {
            log.info("[bi] 业务数据已存在，跳过假数据生成（force=true 可清空重造）");
            return Map.of("skipped", true);
        }

        Random rnd = new Random(SEED);

        // ---- 区域：4 个大区 + 12 个省市级子区域 ----
        Map<String, List<String>> regionTree = new LinkedHashMap<>();
        regionTree.put("华东", List.of("上海", "江苏", "浙江"));
        regionTree.put("华南", List.of("广东", "福建"));
        regionTree.put("华北", List.of("北京", "天津", "河北"));
        regionTree.put("西南", List.of("四川", "重庆"));

        List<BiRegion> topRegions = new ArrayList<>();
        int topIndex = 0;
        for (Map.Entry<String, List<String>> entry : regionTree.entrySet()) {
            BiRegion top = new BiRegion();
            top.setName(entry.getKey());
            top.setCode("R" + (topIndex + 1));
            top.setLevel(BiRegion.LEVEL_MACRO);
            top = regionRepository.save(top);

            for (String childName : entry.getValue()) {
                BiRegion child = new BiRegion();
                child.setName(childName);
                child.setCode("R" + (topIndex + 1) + "-" + childName);
                child.setLevel(BiRegion.LEVEL_PROVINCIAL);
                child.setParentId(top.getId());
                regionRepository.save(child);
            }
            topRegions.add(top);
            topIndex++;
        }

        // 子区域 id 列表（用于流水生成），权重与父区域一致
        List<List<Long>> childrenByTop = new ArrayList<>();
        for (BiRegion top : topRegions) {
            List<Long> childIds = new ArrayList<>();
            for (BiRegion r : regionRepository.findAll()) {
                if (top.getId().equals(r.getParentId())) {
                    childIds.add(r.getId());
                }
            }
            childrenByTop.add(childIds);
        }
        double[] regionWeights = {0.40, 0.25, 0.20, 0.15};

        // ---- 渠道 ----
        Object[][] channelData = {
                {"TMALL", "天猫旗舰店", BiChannel.TYPE_ONLINE},
                {"JD", "京东自营", BiChannel.TYPE_ONLINE},
                {"DOUYIN", "抖音直播", BiChannel.TYPE_ONLINE},
                {"PDD", "拼多多", BiChannel.TYPE_ONLINE},
                {"WXMP", "微信小程序", BiChannel.TYPE_ONLINE},
                {"STORE", "线下门店", BiChannel.TYPE_OFFLINE},
        };
        List<BiChannel> channels = new ArrayList<>();
        for (Object[] c : channelData) {
            BiChannel ch = new BiChannel();
            ch.setCode((String) c[0]);
            ch.setName((String) c[1]);
            ch.setType((String) c[2]);
            channels.add(channelRepository.save(ch));
        }

        // ---- 商品 ----
        Object[][] productData = {
                {"SKU-1001", "便携榨汁杯", "家电", new BigDecimal("129.00")},
                {"SKU-1002", "无线降噪耳机", "数码", new BigDecimal("399.00")},
                {"SKU-1003", "扫地机器人", "家电", new BigDecimal("1299.00")},
                {"SKU-1004", "保温杯", "日用", new BigDecimal("79.00")},
                {"SKU-1005", "电动牙刷", "个护", new BigDecimal("219.00")},
                {"SKU-1006", "修护面膜礼盒", "美妆", new BigDecimal("129.00")},
                {"SKU-1007", "混合坚果礼盒", "食品", new BigDecimal("89.00")},
                {"SKU-1008", "挂耳咖啡礼盒", "食品", new BigDecimal("119.00")},
                {"SKU-1009", "智能手表", "数码", new BigDecimal("899.00")},
                {"SKU-1010", "眼部按摩仪", "个护", new BigDecimal("399.00")},
        };
        List<BiProduct> products = new ArrayList<>();
        for (Object[] p : productData) {
            BiProduct pr = new BiProduct();
            pr.setSku((String) p[0]);
            pr.setName((String) p[1]);
            pr.setCategory((String) p[2]);
            pr.setPrice((BigDecimal) p[3]);
            products.add(productRepository.save(pr));
        }

        // ---- 活动：窗口为当前年的自然日。相对"今天"滚动，保证数据窗口内有大促 ----
        int year = LocalDate.now().getYear();
        Object[][] activityData = {
                {"618大促", BiActivity.TYPE_PROMO, 6, 1, 6, 20, "0.85"},
                {"会员日", BiActivity.TYPE_MEMBER, 3, 8, 3, 8, "0.90"},
                {"开学季特惠", BiActivity.TYPE_PROMO, 8, 20, 9, 5, "0.90"},
                {"国庆中秋双节庆", BiActivity.TYPE_PROMO, 9, 28, 10, 8, "0.88"},
                {"双11预售", BiActivity.TYPE_LIVE, 11, 1, 11, 15, "0.82"},
        };
        List<BiActivity> activities = new ArrayList<>();
        for (Object[] a : activityData) {
            BiActivity ac = new BiActivity();
            ac.setName((String) a[0]);
            ac.setType((String) a[1]);
            ac.setStartDate(LocalDate.of(year, (Integer) a[2], (Integer) a[3]));
            ac.setEndDate(LocalDate.of(year, (Integer) a[4], (Integer) a[5]));
            ac.setDiscount(new BigDecimal((String) a[6]));
            LocalDate now = LocalDate.now();
            if (now.isBefore(ac.getStartDate())) {
                ac.setStatus(BiActivity.STATUS_NOT_STARTED);
            } else if (!now.isAfter(ac.getEndDate())) {
                ac.setStatus(BiActivity.STATUS_ONGOING);
            } else {
                ac.setStatus(BiActivity.STATUS_ENDED);
            }
            activities.add(activityRepository.save(ac));
        }

        // ---- 销售流水 ----
        List<BiSalesRecord> buffer = new ArrayList<>();
        int totalDays = seedDays;
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays((long) (totalDays - 1));
        int produced = 0;
        for (int i = 0; i < totalDays; i++) {
            LocalDate d = start.plusDays(i);
            if (d.isAfter(today)) {
                break;
            }
            double trend = 0.85 + 1.0 * i / Math.max(totalDays - 1, 1); // 季度内缓慢上行
            DayOfWeek dow = d.getDayOfWeek();
            boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            BiActivity ac = findActivity(activities, d);

            for (BiChannel ch : channels) {
                int n = 2 + rnd.nextInt(4);
                if (ac != null) {
                    n += 2; // 活动日销量上浮
                }
                if (weekend && rnd.nextDouble() < 0.6) {
                    n += 1;
                }
                for (int k = 0; k < n; k++) {
                    BiProduct pr = products.get(rnd.nextInt(products.size()));
                    BiRegion rg = pickRegion(rnd, childrenByTop, regionWeights);
                    int quantity = 1 + rnd.nextInt(ac != null ? 5 : 3);
                    BigDecimal amount = pr.getPrice()
                            .multiply(BigDecimal.valueOf(quantity))
                            .multiply(ac != null ? ac.getDiscount() : BigDecimal.ONE)
                            .multiply(BigDecimal.valueOf(0.95 + rnd.nextDouble() * 0.10))
                            .setScale(2, RoundingMode.HALF_UP);
                    BiSalesRecord record = new BiSalesRecord();
                    record.setStatDate(d);
                    record.setChannelId(ch.getId());
                    record.setProductId(pr.getId());
                    record.setRegionId(rg.getId());
                    record.setActivityId(ac != null && ac.getDiscount().compareTo(BigDecimal.ONE) < 0
                            ? ac.getId() : null);
                    record.setQuantity(quantity);
                    record.setAmount(amount);
                    buffer.add(record);
                    produced++;
                }
            }
            if (buffer.size() >= 500) {
                salesRepository.saveAll(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            salesRepository.saveAll(buffer);
        }
        buffer.clear();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("regions", regionRepository.count());
        resp.put("channels", channelRepository.count());
        resp.put("products", productRepository.count());
        resp.put("activities", activityRepository.count());
        resp.put("salesRecords", salesRepository.count());
        log.info("[bi] 假数据生成完成：本轮写入 {} 条销售流水，{}", produced, resp);
        return resp;
    }

    /** 按起始日期找命中的活动（可能不止一个窗口重叠，取第一个命中） */
    private BiActivity findActivity(List<BiActivity> activities, LocalDate date) {
        for (BiActivity ac : activities) {
            if (!date.isBefore(ac.getStartDate()) && !date.isAfter(ac.getEndDate())) {
                return ac;
            }
        }
        return null;
    }

    /** 按权重选大区，再在大区内均匀随机选一个子区域 */
    private BiRegion pickRegion(Random rnd, List<List<Long>> childrenByTop, double[] weights) {
        double roll = rnd.nextDouble();
        double acc = 0;
        int idx = childrenByTop.size() - 1;
        for (int i = 0; i < childrenByTop.size(); i++) {
            acc += weights[i];
            if (roll < acc) {
                idx = i;
                break;
            }
        }
        List<Long> children = childrenByTop.get(idx);
        if (children.isEmpty()) {
            return new BiRegion(); // 理论上不会走到
        }
        List<Long> shuffled = new ArrayList<>(children);
        Collections.shuffle(shuffled, rnd);
        // 不能直接返回引用并赋 id —— 返回一个仅持有 id 的代理，供写入使用
        BiRegion proxy = new BiRegion();
        proxy.setId(shuffled.get(rnd.nextInt(shuffled.size())));
        return proxy;
    }
}
