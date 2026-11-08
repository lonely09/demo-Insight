package com.insightserver.bi.audit;

import com.insightserver.bi.agent.BiPlanInfo;
import com.insightserver.bi.agent.BiPrincipal;
import com.insightserver.bi.agent.BiQueryResult;
import com.insightserver.bi.agent.BiQuerySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 审计留痕写入/查询：每次提问都落库，成功与失败都留下可追溯的规格和结果指纹 */
@Service
public class BiAuditService {

    private final BiAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public BiAuditService(BiAuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public BiAuditLog recordSuccess(String traceId, BiPrincipal principal, BiQuerySpec spec,
                                    BiQueryResult result, BiPlanInfo planInfo, long durationMs) {
        BiAuditLog log = base(traceId, principal, spec, planInfo, durationMs);
        log.setStatus("SUCCESS");
        log.setMetricName(result.getMeta().metricName());
        log.setMetricCaliber(result.getMeta().caliber());
        log.setRowCount(result.getMeta().rowCount());
        log.setTotalAmount(result.getTotals().amount());
        log.setTotalQuantity(result.getTotals().quantity());
        log.setAnswer(result.getAnswer());
        log.setResultHash(result.getMeta().resultHash());
        log.setResultJson(toJson(result));
        log.setFiltersText(filtersText(result));
        return repository.save(log);
    }

    public BiAuditLog recordFailure(String traceId, String question, BiPrincipal principal,
                                    BiQuerySpec spec, BiPlanInfo planInfo,
                                    String status, String error, long durationMs) {
        BiAuditLog log = base(traceId, principal, spec, planInfo, durationMs);
        log.setQuestion(question);
        log.setStatus(status);
        log.setErrorMessage(error);
        if (spec != null) {
            log.setFiltersText(spec.getFilters().summaryParts().toString());
        }
        return repository.save(log);
    }

    private BiAuditLog base(String traceId, BiPrincipal principal, BiQuerySpec spec,
                            BiPlanInfo planInfo, long durationMs) {
        BiAuditLog log = new BiAuditLog();
        log.setTraceId(traceId);
        log.setUserId(principal.userId());
        log.setRole(principal.role());
        log.setScopeLabel(principal.scopeLabel());
        log.setDurationMs(durationMs);
        log.setExported(false);
        BiPlanInfo plan = planInfo == null ? BiPlanInfo.rule() : planInfo;
        log.setPlanner(plan.planner());
        log.setLlmModel(plan.model());
        log.setLlmRawOutput(plan.rawOutput());
        log.setLlmError(plan.note());
        if (spec != null) {
            log.setQuestion(spec.getQuestion());
            log.setMetricKey(spec.getMetricKey());
            log.setDateFrom(spec.getDateFrom());
            log.setDateTo(spec.getDateTo());
            log.setDateLabel(spec.getDateLabel());
            log.setGroupBy(spec.getGroupBy());
            log.setCompareType(spec.getCompare());
            log.setFiltersJson(toJson(filtersMap(spec)));
        }
        return log;
    }

    private Map<String, Object> filtersMap(BiQuerySpec spec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("channelIds", spec.getFilters().getChannelIds());
        map.put("productIds", spec.getFilters().getProductIds());
        map.put("regionIds", spec.getFilters().getRegionIds());
        map.put("activityIds", spec.getFilters().getActivityIds());
        map.put("categories", spec.getFilters().getCategories());
        map.put("channelType", spec.getFilters().getChannelType());
        return map;
    }

    private String filtersText(BiQueryResult result) {
        if (result.getMeta().filters() == null || result.getMeta().filters().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        result.getMeta().filters().forEach(f -> {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(f.label()).append('=').append(String.join("、", f.values()));
        });
        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public Optional<BiAuditLog> find(String traceId) {
        return repository.findByTraceId(traceId);
    }

    public List<BiAuditLog> recent(int limit, BiPrincipal principal) {
        int size = Math.max(1, Math.min(limit, 100));
        PageRequest page = PageRequest.of(0, size);
        if ("ADMIN".equals(principal.role()) || "ANALYST".equals(principal.role())) {
            return repository.findAllByOrderByIdDesc(page);
        }
        return repository.findByUserIdOrderByIdDesc(principal.userId(), page);
    }

    public void markExported(String traceId) {
        repository.findByTraceId(traceId).ifPresent(log -> {
            log.setExported(true);
            log.setExportedAt(LocalDateTime.now());
            repository.save(log);
        });
    }
}
