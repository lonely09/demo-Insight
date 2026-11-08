package com.insightserver.bi.agent;

import com.insightserver.bi.domain.BiActivity;
import com.insightserver.bi.domain.BiChannel;
import com.insightserver.bi.domain.BiProduct;
import com.insightserver.bi.domain.BiRegion;
import com.insightserver.bi.repository.BiActivityRepository;
import com.insightserver.bi.repository.BiChannelRepository;
import com.insightserver.bi.repository.BiProductRepository;
import com.insightserver.bi.repository.BiRegionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 查询规划器：只把自然语言映射成 BiQuerySpec。
 *
 * 安全边界：
 *  - LLM 只输出 JSON 规划，不直接写 SQL，也不输出任何统计数字；
 *  - 所有指标、维度、实体 id 都必须通过本地校验，未知 id 直接判失败；
 *  - 失败后由 BiAgentService 决定是否回退规则解析；
 *  - 失败会短暂熔断，避免每次提问都等模型超时。
 */
@Component
public class BiLlmPlanner {

    private static final Logger log = LoggerFactory.getLogger(BiLlmPlanner.class);
    private static final Set<String> METRICS = Set.of("amount", "quantity", "records");
    private static final Set<String> GROUPS = Set.of("channel", "product", "category", "region", "activity", "date");
    private static final Set<String> COMPARES = Set.of("mom", "yoy");
    private static final Set<String> GRAINS = Set.of("day", "week", "month");

    private final BiLlmProperties properties;
    private final BiLlmClient client;
    private final BiChannelRepository channelRepository;
    private final BiProductRepository productRepository;
    private final BiRegionRepository regionRepository;
    private final BiActivityRepository activityRepository;
    private final BiMetric metric;
    private final ObjectMapper objectMapper;

    private volatile long cooldownUntil = 0L;

    public BiLlmPlanner(BiLlmProperties properties,
                        BiLlmClient client,
                        BiChannelRepository channelRepository,
                        BiProductRepository productRepository,
                        BiRegionRepository regionRepository,
                        BiActivityRepository activityRepository,
                        BiMetric metric,
                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = client;
        this.channelRepository = channelRepository;
        this.productRepository = productRepository;
        this.regionRepository = regionRepository;
        this.activityRepository = activityRepository;
        this.metric = metric;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public boolean available() {
        return properties.isEnabled()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()
                && System.currentTimeMillis() >= cooldownUntil;
    }

    public String model() {
        return properties.getModel();
    }

    public PlanResult plan(String question) {
        if (!available()) {
            throw new BiLlmException("LLM 未启用、未配置 api-key，或处于失败熔断期");
        }
        long start = System.currentTimeMillis();
        String raw = null;
        try {
            raw = client.chat(buildSystemPrompt(), "用户问题：" + question + "\n只输出 JSON。");
            BiQuerySpec spec = parseSpec(raw, question);
            return new PlanResult(spec, properties.getModel(), raw, System.currentTimeMillis() - start);
        } catch (BiLlmException e) {
            cooldown();
            log.warn("LLM 规划失败，{} 秒内回退规则解析: {}", properties.getFailureCooldownSeconds(), e.getMessage());
            throw new BiLlmException(e.getMessage(), e.getRawOutput() != null ? e.getRawOutput() : raw);
        } catch (Exception e) {
            cooldown();
            log.warn("LLM 规划异常，{} 秒内回退规则解析: {}", properties.getFailureCooldownSeconds(), e.getMessage());
            throw new BiLlmException("LLM 规划异常: " + e.getMessage(), raw == null ? null : raw);
        }
    }

    private void cooldown() {
        long seconds = Math.max(10, properties.getFailureCooldownSeconds());
        cooldownUntil = System.currentTimeMillis() + seconds * 1000L;
    }

    private String buildSystemPrompt() {
        LocalDate today = LocalDate.now();
        List<BiChannel> channels = channelRepository.findAll();
        List<BiProduct> products = productRepository.findAll();
        List<BiRegion> regions = regionRepository.findAll();
        List<BiActivity> activities = activityRepository.findAll();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("你是“对话式数据统计”的查询规划器。只输出一个 JSON 对象，不要 Markdown、不要解释、不要 SQL、不要输出任何统计数字。\n");
        sb.append("今天日期：").append(today).append("（Asia/Shanghai）。数据窗口：最近 120 天。\n\n");

        sb.append("可用指标 metric：\n");
        for (String key : metric.keys()) {
            if (!METRICS.contains(key)) {
                continue;
            }
            BiMetricDef def = metric.metric(key);
            sb.append("- ").append(key).append("：").append(def.getDisplayName())
                    .append("；口径：").append(def.getCaliber()).append('\n');
        }

        sb.append("\n可用分组维度 groupBy：channel / product / category / region / activity / date；不分组用 null。\n");
        sb.append("可选对比 compare：mom（环比）/ yoy（同比）/ null。\n");
        sb.append("可选 grain：day / week / month（仅 groupBy=date 时有意义）。\n\n");

        sb.append("渠道（channelIds 只能使用以下 id）：\n");
        for (BiChannel c : channels) {
            sb.append("- id=").append(c.getId()).append(", code=").append(c.getCode())
                    .append(", name=").append(c.getName()).append(", type=").append(c.getType()).append('\n');
        }
        sb.append("\n商品（productIds 只能使用以下 id）：\n");
        for (BiProduct p : products) {
            sb.append("- id=").append(p.getId()).append(", sku=").append(p.getSku())
                    .append(", name=").append(p.getName()).append(", category=").append(p.getCategory())
                    .append(", price=").append(p.getPrice()).append('\n');
        }
        sb.append("\n区域（regionIds 只能使用以下 id；parentId 表示上级区域）：\n");
        for (BiRegion r : regions) {
            sb.append("- id=").append(r.getId()).append(", name=").append(r.getName())
                    .append(", level=").append(r.getLevel())
                    .append(", parentId=").append(r.getParentId() == null ? "null" : r.getParentId())
                    .append('\n');
        }
        sb.append("\n活动（activityIds 只能使用以下 id；问题提到活动时，dateFrom/dateTo 优先用活动窗口）：\n");
        for (BiActivity a : activities) {
            sb.append("- id=").append(a.getId()).append(", name=").append(a.getName())
                    .append(", start=").append(a.getStartDate()).append(", end=").append(a.getEndDate())
                    .append(", discount=").append(a.getDiscount()).append('\n');
        }
        sb.append("\n可选品类 categories：")
                .append(products.stream().map(BiProduct::getCategory).distinct().collect(Collectors.joining(" / ")))
                .append("\n\n");

        sb.append("""
                输出 JSON 结构（所有字段都要出现，没有就填 null 或空数组）：
                {
                  "metric": "amount",
                  "dateFrom": "YYYY-MM-DD",
                  "dateTo": "YYYY-MM-DD",
                  "dateLabel": "上个月",
                  "groupBy": null,
                  "grain": "day",
                  "compare": null,
                  "share": false,
                  "drill": false,
                  "ascending": false,
                  "limit": 10,
                  "filters": {
                    "channelIds": [],
                    "productIds": [],
                    "regionIds": [],
                    "activityIds": [],
                    "categories": [],
                    "channelType": null
                  }
                }

                规划规则：
                1. 未指定时间时，默认最近 30 天：dateFrom = today-29，dateTo = today。
                2. “上个月”是上一个自然月；“本月”是本月 1 日到今天；“去年”是去年全年。
                3. “各渠道/按渠道” -> groupBy=channel；“各商品” -> product；“各品类” -> category；“各区域” -> region；“各活动” -> activity；“趋势/每天/每月” -> date。
                4. “环比” -> compare=mom；“同比” -> compare=yoy；否则 null。
                5. “占比/份额” -> share=true；“下钻/细分” -> drill=true，未指定 groupBy 时用 region。
                6. 只能使用上面列出的 id，禁止编造；不确定就留空数组。
                7. 不要输出 SQL、结果数字、解释或 Markdown 代码块。
                """);
        return sb.toString();
    }

    private BiQuerySpec parseSpec(String raw, String question) {
        String json = extractJson(raw);
        try {
            JsonNode root = objectMapper.readTree(json);
            BiQuerySpec spec = new BiQuerySpec();
            spec.setQuestion(question);

            String metricKey = text(root, "metric", "amount");
            if (!METRICS.contains(metricKey)) {
                throw new BiLlmException("LLM 返回未知指标: " + metricKey, raw);
            }
            spec.setMetricKey(metricKey);

            LocalDate today = LocalDate.now();
            LocalDate from = date(root, "dateFrom", today.minusDays(29));
            LocalDate to = date(root, "dateTo", today);
            if (to.isBefore(from)) {
                LocalDate tmp = from;
                from = to;
                to = tmp;
            }
            spec.setDateFrom(from);
            spec.setDateTo(to);
            spec.setDateLabel(text(root, "dateLabel", from + " 至 " + to));

            String group = nullableText(root, "groupBy");
            if (group != null && !GROUPS.contains(group)) {
                throw new BiLlmException("LLM 返回未知分组: " + group, raw);
            }
            if ("date".equals(group)) {
                String grain = text(root, "grain", "day");
                if (!GRAINS.contains(grain)) {
                    throw new BiLlmException("LLM 返回未知粒度: " + grain, raw);
                }
                spec.setGrain(grain);
            }
            spec.setGroupBy(group);

            String compare = nullableText(root, "compare");
            if (compare != null && !COMPARES.contains(compare)) {
                throw new BiLlmException("LLM 返回未知对比: " + compare, raw);
            }
            spec.setCompare(compare);
            spec.setShare(bool(root, "share", false));
            spec.setDrill(bool(root, "drill", false));
            spec.setAscending(bool(root, "ascending", false));
            spec.setLimit(intValue(root, "limit", 10));
            if (spec.isDrill() && spec.getGroupBy() == null) {
                spec.setGroupBy("region");
            }

            JsonNode filters = root.path("filters");
            Set<Long> channelIds = ids(channelRepository.findAll().stream().map(BiChannel::getId).collect(Collectors.toSet()));
            Set<Long> productIds = ids(productRepository.findAll().stream().map(BiProduct::getId).collect(Collectors.toSet()));
            Set<Long> regionIds = ids(regionRepository.findAll().stream().map(BiRegion::getId).collect(Collectors.toSet()));
            Set<Long> activityIds = ids(activityRepository.findAll().stream().map(BiActivity::getId).collect(Collectors.toSet()));
            Set<String> categories = productRepository.findAll().stream()
                    .map(BiProduct::getCategory).collect(Collectors.toSet());

            readIds(filters, "channelIds", channelIds, "渠道", raw, spec.getFilters()::addChannel);
            readIds(filters, "productIds", productIds, "商品", raw, spec.getFilters()::addProduct);
            readIds(filters, "regionIds", regionIds, "区域", raw, spec.getFilters()::addRegion);
            readIds(filters, "activityIds", activityIds, "活动", raw, spec.getFilters()::addActivity);
            readCategories(filters, categories, raw, spec);
            String channelType = nullableText(filters, "channelType");
            if (channelType != null) {
                if (!"ONLINE".equals(channelType) && !"OFFLINE".equals(channelType)) {
                    throw new BiLlmException("LLM 返回未知渠道类型: " + channelType, raw);
                }
                spec.getFilters().setChannelType(channelType);
            }
            return spec;
        } catch (BiLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new BiLlmException("LLM JSON 解析失败: " + e.getMessage(), raw);
        }
    }

    private String extractJson(String raw) {
        if (raw == null) {
            throw new BiLlmException("LLM 返回为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BiLlmException("LLM 返回中没有 JSON 对象", raw);
        }
        return raw.substring(start, end + 1);
    }

    private void readIds(JsonNode filters, String field, Set<Long> allowed,
                         String label, String raw, java.util.function.LongConsumer consumer) {
        JsonNode array = filters.path(field);
        if (!array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            long id = node.asLong(-1);
            if (!allowed.contains(id)) {
                throw new BiLlmException("LLM 返回未知" + label + " id: " + id, raw);
            }
            consumer.accept(id);
        }
    }

    private void readCategories(JsonNode filters, Set<String> allowed, String raw, BiQuerySpec spec) {
        JsonNode array = filters.path("categories");
        if (!array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            String category = node.asText();
            if (category == null || category.isBlank()) {
                continue;
            }
            if (!allowed.contains(category)) {
                throw new BiLlmException("LLM 返回未知品类: " + category, raw);
            }
            spec.getFilters().addCategory(category);
        }
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? defaultValue : value.asText();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asBoolean(defaultValue);
    }

    private int intValue(JsonNode node, String field, int defaultValue) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asInt(defaultValue);
    }

    private LocalDate date(JsonNode node, String field, LocalDate defaultValue) {
        String value = nullableText(node, field);
        if (value == null) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw new BiLlmException("LLM 日期格式错误: " + field + "=" + value);
        }
    }

    private Set<Long> ids(Set<Long> source) {
        return new HashSet<>(source);
    }

    public record PlanResult(BiQuerySpec spec, String model, String rawOutput, long durationMs) {
    }
}
