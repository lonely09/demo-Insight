package com.insightserver.bi.web;

import com.insightserver.bi.agent.BiAgentService;
import com.insightserver.bi.agent.BiLlmProperties;
import com.insightserver.bi.agent.BiMetric;
import com.insightserver.bi.agent.BiMetricDef;
import com.insightserver.bi.agent.BiPermissionService;
import com.insightserver.bi.agent.BiPrincipal;
import com.insightserver.bi.agent.BiQueryResult;
import com.insightserver.bi.agent.BiQuerySpec;
import com.insightserver.bi.audit.BiAuditLog;
import com.insightserver.bi.audit.BiAuditService;
import com.insightserver.bi.support.BiAccessDeniedException;
import com.insightserver.bi.support.BiServiceException;
import com.insightserver.bi.web.dto.BiAgentReq;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话式数据统计 API。
 *
 * 身份头（Demo 用，生产替换为网关注入/SSO）：
 *   X-Bi-User       用户标识
 *   X-Bi-Role       ADMIN / ANALYST / BUSINESS_USER / VIEWER
 *   X-Bi-Region-Id  业务用户的区域范围（ID/编码/名称，含下级）
 */
@RestController
@RequestMapping("/api/bi/agent")
public class BiAgentController {

    private final BiAgentService agentService;
    private final BiPermissionService permissionService;
    private final BiMetric metric;
    private final BiLlmProperties llmProperties;
    private final BiAuditService auditService;
    private final ObjectMapper objectMapper;

    public BiAgentController(BiAgentService agentService,
                             BiPermissionService permissionService,
                             BiMetric metric,
                             BiLlmProperties llmProperties,
                             BiAuditService auditService,
                             ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.permissionService = permissionService;
        this.metric = metric;
        this.llmProperties = llmProperties;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/ask")
    public BiQueryResult ask(@Valid @RequestBody BiAgentReq req,
                             @RequestHeader(value = "X-Bi-User", required = false) String user,
                             @RequestHeader(value = "X-Bi-Role", required = false) String role,
                             @RequestHeader(value = "X-Bi-Region-Id", required = false) String scope,
                             @RequestHeader(value = "X-Bi-Region-Scope", required = false) String scopeAlias) {
        BiPrincipal principal = principal(user, role, scope, scopeAlias);
        return agentService.ask(req.question(), principal);
    }

    /** 只解析不统计：用于排查"问题被理解成了什么"，也便于业务方核对口径 */
    @PostMapping("/parse")
    public Map<String, Object> parse(@Valid @RequestBody BiAgentReq req,
                                     @RequestHeader(value = "X-Bi-User", required = false) String user,
                                     @RequestHeader(value = "X-Bi-Role", required = false) String role,
                                     @RequestHeader(value = "X-Bi-Region-Id", required = false) String scope) {
        principal(user, role, scope, null);
        BiAgentService.PlanOutcome outcome = agentService.planOnly(req.question());
        BiQuerySpec spec = outcome.spec();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("planner", outcome.info().planner());
        resp.put("model", outcome.info().model());
        resp.put("note", outcome.info().note());
        resp.put("rawOutput", outcome.info().rawOutput());
        resp.put("spec", spec);
        return resp;
    }

    /** 指标口径目录 + 支持的维度/对比/示例问题，供前端/业务方查询 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (String key : metric.keys()) {
            BiMetricDef def = metric.metric(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", def.getKey());
            item.put("name", def.getDisplayName());
            item.put("caliber", def.getCaliber());
            item.put("formula", def.getFormula());
            item.put("unit", def.getUnit());
            metrics.add(item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("metrics", metrics);
        resp.put("dimensions", List.of("channel", "product", "category", "region", "activity", "date"));
        resp.put("compares", metric.compareKeys());
        resp.put("drills", metric.drillKeys());
        resp.put("llm", Map.of(
                "enabled", llmProperties.isEnabled(),
                "model", llmProperties.getModel(),
                "fallbackToRule", llmProperties.isFallbackToRule()
        ));
        resp.put("roles", List.of(
                Map.of("role", "ADMIN", "label", "管理员", "scope", "全部区域", "export", true),
                Map.of("role", "ANALYST", "label", "分析师", "scope", "全部区域", "export", true),
                Map.of("role", "BUSINESS_USER", "label", "业务用户", "scope", "必须指定区域", "export", true),
                Map.of("role", "VIEWER", "label", "只读用户", "scope", "全部区域", "export", false)
        ));
        resp.put("sampleQuestions", List.of(
                "最近30天各渠道的成交额",
                "上个月各区域的销量，环比",
                "国庆期间天猫和京东的成交额对比",
                "各品类成交额占比",
                "最近7天成交额趋势",
                "华东区域下钻看各城市成交额",
                "卖得最好的5个商品"
        ));
        return resp;
    }

    /** 审计历史：管理员/分析师看全部，业务用户/只读只看自己 */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "20") int limit,
                                             @RequestHeader(value = "X-Bi-User", required = false) String user,
                                             @RequestHeader(value = "X-Bi-Role", required = false) String role,
                                             @RequestHeader(value = "X-Bi-Region-Id", required = false) String scope) {
        BiPrincipal principal = principal(user, role, scope, null);
        List<Map<String, Object>> list = new ArrayList<>();
        for (BiAuditLog log : auditService.recent(limit, principal)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("traceId", log.getTraceId());
            item.put("userId", log.getUserId());
            item.put("role", log.getRole());
            item.put("question", log.getQuestion());
            item.put("metric", log.getMetricName());
            item.put("planner", log.getPlanner());
            item.put("dateFrom", log.getDateFrom());
            item.put("dateTo", log.getDateTo());
            item.put("rowCount", log.getRowCount());
            item.put("totalAmount", log.getTotalAmount());
            item.put("status", log.getStatus());
            item.put("exported", log.isExported());
            item.put("createdAt", log.getCreatedAt());
            list.add(item);
        }
        return list;
    }

    /** 按 traceId 追溯：问题、规格、口径、结果指纹、是否导出 */
    @GetMapping("/trace/{traceId}")
    public Map<String, Object> trace(@PathVariable String traceId,
                                     @RequestHeader(value = "X-Bi-User", required = false) String user,
                                     @RequestHeader(value = "X-Bi-Role", required = false) String role,
                                     @RequestHeader(value = "X-Bi-Region-Id", required = false) String scope) {
        BiPrincipal principal = principal(user, role, scope, null);
        BiAuditLog log = auditService.find(traceId)
                .orElseThrow(() -> new BiServiceException("查询记录不存在: traceId=" + traceId));
        boolean privileged = principal.isAdmin() || "ANALYST".equals(principal.role());
        if (!privileged && !log.getUserId().equals(principal.userId())) {
            throw new BiAccessDeniedException("无权查看其他用户的查询记录");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("traceId", log.getTraceId());
        resp.put("userId", log.getUserId());
        resp.put("role", log.getRole());
        resp.put("scopeLabel", log.getScopeLabel());
        resp.put("question", log.getQuestion());
        resp.put("metricKey", log.getMetricKey());
        resp.put("metricName", log.getMetricName());
        resp.put("metricCaliber", log.getMetricCaliber());
        resp.put("dateFrom", log.getDateFrom());
        resp.put("dateTo", log.getDateTo());
        resp.put("dateLabel", log.getDateLabel());
        resp.put("groupBy", log.getGroupBy());
        resp.put("compareType", log.getCompareType());
        resp.put("filtersJson", readJson(log.getFiltersJson()));
        resp.put("filtersText", log.getFiltersText());
        resp.put("rowCount", log.getRowCount());
        resp.put("totalAmount", log.getTotalAmount());
        resp.put("totalQuantity", log.getTotalQuantity());
        resp.put("resultHash", log.getResultHash());
        resp.put("status", log.getStatus());
        resp.put("planner", log.getPlanner());
        resp.put("llmModel", log.getLlmModel());
        resp.put("llmRawOutput", log.getLlmRawOutput());
        resp.put("llmError", log.getLlmError());
        resp.put("errorMessage", log.getErrorMessage());
        resp.put("exported", log.isExported());
        resp.put("exportedAt", log.getExportedAt());
        resp.put("durationMs", log.getDurationMs());
        resp.put("createdAt", log.getCreatedAt());
        return resp;
    }

    /** 按 traceId 导出 CSV：导出的是当时保存的结果快照，不重新取数 */
    @GetMapping("/export/{traceId}")
    public ResponseEntity<byte[]> export(@PathVariable String traceId,
                                         @RequestHeader(value = "X-Bi-User", required = false) String user,
                                         @RequestHeader(value = "X-Bi-Role", required = false) String role,
                                         @RequestHeader(value = "X-Bi-Region-Id", required = false) String scope) {
        BiPrincipal principal = principal(user, role, scope, null);
        BiAgentService.CsvFile file = agentService.exportByTrace(traceId, principal);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"");
        headers.set("X-Bi-Trace-Id", traceId);
        return new ResponseEntity<>(file.content().getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    /** 直接"提问即导出"：先查再导出，同样落审计 */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportByQuestion(@Valid @RequestBody BiAgentReq req,
                                                   @RequestHeader(value = "X-Bi-User", required = false) String user,
                                                   @RequestHeader(value = "X-Bi-Role", required = false) String role,
                                                   @RequestHeader(value = "X-Bi-Region-Id", required = false) String scope) {
        BiPrincipal principal = principal(user, role, scope, null);
        BiAgentService.CsvFile file = agentService.exportByQuestion(req.question(), principal);
        return csvResponse(file);
    }

    private ResponseEntity<byte[]> csvResponse(BiAgentService.CsvFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"");
        return new ResponseEntity<>(file.content().getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    private BiPrincipal principal(String user, String role, String scope, String scopeAlias) {
        String region = scope != null && !scope.isBlank() ? scope : scopeAlias;
        return permissionService.resolve(user, role, region);
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return json;
        }
    }
}
