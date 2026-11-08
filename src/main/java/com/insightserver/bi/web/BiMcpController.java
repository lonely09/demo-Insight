package com.insightserver.bi.web;

import com.insightserver.bi.agent.BiAgentService;
import com.insightserver.bi.agent.BiLlmProperties;
import com.insightserver.bi.agent.BiMetric;
import com.insightserver.bi.agent.BiMetricDef;
import com.insightserver.bi.agent.BiPermissionService;
import com.insightserver.bi.agent.BiPlanInfo;
import com.insightserver.bi.agent.BiPrincipal;
import com.insightserver.bi.agent.BiQueryResult;
import com.insightserver.bi.agent.BiQuerySpec;
import com.insightserver.bi.audit.BiAuditLog;
import com.insightserver.bi.audit.BiAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 轻量 MCP Server（Streamable HTTP / JSON-RPC 2.0）：
 * 把对话式统计 Agent 暴露成 MCP 工具，供 Claude Desktop、Cursor、Dify 等 MCP 客户端调用。
 *
 * 已实现 MCP 基础方法：initialize / notifications/initialized / ping / tools/list / tools/call。
 * 传输层只实现 POST JSON-RPC（返回 application/json），不实现 stdio 和 SSE GET；
 * 对绝大多数支持 Streamable HTTP 的客户端已经够用。
 *
 * 身份沿用 X-Bi-User / X-Bi-Role / X-Bi-Region-Id 请求头；生产应放在网关认证之后。
 */
@RestController
public class BiMcpController {

    private static final String LATEST_PROTOCOL = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOLS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    private final BiAgentService agentService;
    private final BiAuditService auditService;
    private final BiPermissionService permissionService;
    private final BiMetric metric;
    private final BiLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public BiMcpController(BiAgentService agentService,
                           BiAuditService auditService,
                           BiPermissionService permissionService,
                           BiMetric metric,
                           BiLlmProperties llmProperties,
                           ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.metric = metric;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(@RequestBody JsonNode request,
                                    @RequestHeader(value = "X-Bi-User", required = false) String user,
                                    @RequestHeader(value = "X-Bi-Role", required = false) String role,
                                    @RequestHeader(value = "X-Bi-Region-Id", required = false) String scope,
                                    @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {
        if (request == null || !request.isObject()) {
            return ResponseEntity.ok(error(null, -32600, "Invalid Request"));
        }

        String method = request.path("method").asText("");
        JsonNode id = request.get("id");

        // JSON-RPC notification（如 notifications/initialized）不需要响应体
        if (id == null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }

        return switch (method) {
            case "initialize" -> initialize(id, request.path("params"), sessionId);
            case "ping" -> ResponseEntity.ok(result(id, objectMapper.createObjectNode()));
            case "tools/list" -> ResponseEntity.ok(result(id, toolsList()));
            case "tools/call" -> ResponseEntity.ok(result(id,
                    callTool(request.path("params"), user, role, scope)));
            default -> ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
        };
    }

    @GetMapping("/mcp")
    public ResponseEntity<?> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "jsonrpc", "2.0",
                        "error", Map.of("code", -32000,
                                "message", "SSE/GET transport is not enabled; use POST JSON-RPC at /mcp")
                ));
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<?> delete() {
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> initialize(JsonNode id, JsonNode params, String sessionId) {
        String requested = params.path("protocolVersion").asText(LATEST_PROTOCOL);
        String protocol = SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL;

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", protocol);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "insight-server-bi-agent");
        serverInfo.put("version", "0.1.0");
        result.put("instructions", "业务数据统计 MCP Server：提供指标口径、自然语言问数、查询解析与审计追溯。"
                + "统计数字由后端引擎计算，LLM 只做查询规划。");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Mcp-Session-Id", sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : sessionId);
        return new ResponseEntity<>(result(id, result), headers, HttpStatus.OK);
    }

    private ObjectNode toolsList() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(tool("bi_metrics",
                "列出可用的指标、口径、分组维度、对比方式与 LLM 规划状态。",
                objectSchema(Map.of())));
        tools.add(tool("bi_ask",
                "用自然语言查询业务数据。返回自然语言结论、分组/趋势/对比/下钻数据、口径、权限、traceId 与结果指纹。",
                objectSchema(Map.of("question", stringProperty("业务问题，例如：上个月各区域的销量，环比")))));
        tools.add(tool("bi_parse",
                "只做查询规划，不执行统计。返回 planner（LLM/RULE/RULE_FALLBACK）、BiQuerySpec 与 LLM 原始输出，便于排查问题被理解成了什么。",
                objectSchema(Map.of("question", stringProperty("业务问题")))));
        tools.add(tool("bi_trace",
                "按 traceId 查询审计记录，返回问题、口径、筛选、结果指纹、解析方式与导出状态。",
                objectSchema(Map.of("traceId", stringProperty("bi_ask 返回的 traceId")))));
        return result;
    }

    private ObjectNode callTool(JsonNode params, String user, String role, String scope) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");
        try {
            BiPrincipal principal = permissionService.resolve(user, role, scope);
            return switch (name) {
                case "bi_metrics" -> toolResult(metricsPayload(), "指标口径目录已返回", false);
                case "bi_ask" -> {
                    String question = requiredQuestion(arguments);
                    BiQueryResult result = agentService.ask(question, principal);
                    String text = result.getAnswer() + "\n\ntraceId=" + result.getMeta().traceId();
                    yield toolResult(objectMapper.valueToTree(result), text, false);
                }
                case "bi_parse" -> {
                    String question = requiredQuestion(arguments);
                    BiAgentService.PlanOutcome outcome = agentService.planOnly(question);
                    yield toolResult(planPayload(outcome), "查询规划已返回", false);
                }
                case "bi_trace" -> {
                    String traceId = arguments.path("traceId").asText("");
                    if (traceId.isBlank()) {
                        yield toolError("traceId 不能为空");
                    }
                    BiAuditLog log = auditService.find(traceId).orElse(null);
                    if (log == null) {
                        yield toolError("查询记录不存在: traceId=" + traceId);
                    }
                    boolean privileged = principal.isAdmin() || "ANALYST".equals(principal.role());
                    if (!privileged && !log.getUserId().equals(principal.userId())) {
                        yield toolError("无权查看其他用户的查询记录");
                    }
                    yield toolResult(objectMapper.valueToTree(tracePayload(log)), "审计记录已返回", false);
                }
                default -> toolError("未知工具: " + name);
            };
        } catch (Exception e) {
            return toolError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private ObjectNode metricsPayload() {
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metrics", metrics);
        payload.put("dimensions", List.of("channel", "product", "category", "region", "activity", "date"));
        payload.put("compares", metric.compareKeys());
        payload.put("llm", Map.of(
                "enabled", llmProperties.isEnabled(),
                "model", llmProperties.getModel(),
                "fallbackToRule", llmProperties.isFallbackToRule()));
        payload.put("sampleQuestions", List.of(
                "最近30天各渠道的成交额",
                "上个月各区域的销量，环比",
                "国庆期间天猫和京东的成交额对比",
                "各品类成交额占比",
                "华东区域下钻看各城市成交额"
        ));
        return objectMapper.valueToTree(payload);
    }

    private ObjectNode planPayload(BiAgentService.PlanOutcome outcome) {
        BiQuerySpec spec = outcome.spec();
        BiPlanInfo info = outcome.info();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planner", info.planner());
        payload.put("label", info.label());
        payload.put("model", info.model());
        payload.put("note", info.note());
        payload.put("rawOutput", info.rawOutput());
        payload.put("spec", spec);
        return objectMapper.valueToTree(payload);
    }

    private Map<String, Object> tracePayload(BiAuditLog log) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", log.getTraceId());
        payload.put("userId", log.getUserId());
        payload.put("role", log.getRole());
        payload.put("scopeLabel", log.getScopeLabel());
        payload.put("question", log.getQuestion());
        payload.put("metricKey", log.getMetricKey());
        payload.put("metricName", log.getMetricName());
        payload.put("metricCaliber", log.getMetricCaliber());
        payload.put("dateFrom", log.getDateFrom());
        payload.put("dateTo", log.getDateTo());
        payload.put("groupBy", log.getGroupBy());
        payload.put("compareType", log.getCompareType());
        payload.put("filtersText", log.getFiltersText());
        payload.put("rowCount", log.getRowCount());
        payload.put("totalAmount", log.getTotalAmount());
        payload.put("totalQuantity", log.getTotalQuantity());
        payload.put("resultHash", log.getResultHash());
        payload.put("status", log.getStatus());
        payload.put("planner", log.getPlanner());
        payload.put("llmModel", log.getLlmModel());
        payload.put("llmError", log.getLlmError());
        payload.put("exported", log.isExported());
        payload.put("exportedAt", log.getExportedAt());
        payload.put("durationMs", log.getDurationMs());
        payload.put("createdAt", log.getCreatedAt());
        return payload;
    }

    private String requiredQuestion(JsonNode args) {
        String question = args.path("question").asText("");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (question.length() > 512) {
            throw new IllegalArgumentException("question 最多 512 字符");
        }
        return question;
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private ObjectNode objectSchema(Map<String, ObjectNode> properties) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.forEach(props::set);
        ArrayNode required = schema.putArray("required");
        properties.keySet().forEach(required::add);
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode property = objectMapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private ObjectNode toolResult(JsonNode structured, String text, boolean isError) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text == null ? "" : text);
        if (structured != null) {
            result.set("structuredContent", structured);
        }
        result.put("isError", isError);
        return result;
    }

    private ObjectNode toolError(String message) {
        return toolResult(null, "工具执行失败：" + message, true);
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        } else {
            response.putNull("id");
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
