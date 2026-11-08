package com.insightserver.bi.agent;

import com.insightserver.bi.audit.BiAuditLog;
import com.insightserver.bi.audit.BiAuditService;
import com.insightserver.bi.support.BiAccessDeniedException;
import com.insightserver.bi.support.BiServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 对话式统计 Agent 编排入口：解析 → 权限校验 → 统计 → 生成结论 → 审计。
 * 成功、失败、越权都会落一条审计，traceId 在解析前生成，保证失败也有迹可循。
 */
@Service
public class BiAgentService {

    private static final Logger log = LoggerFactory.getLogger(BiAgentService.class);

    private final BiQueryParser parser;
    private final BiLlmPlanner llmPlanner;
    private final BiLlmProperties llmProperties;
    private final BiQueryEngine engine;
    private final BiAnswerComposer composer;
    private final BiAuditService auditService;
    private final BiExportService exportService;
    private final ObjectMapper objectMapper;

    public BiAgentService(BiQueryParser parser,
                          BiLlmPlanner llmPlanner,
                          BiLlmProperties llmProperties,
                          BiQueryEngine engine,
                          BiAnswerComposer composer,
                          BiAuditService auditService,
                          BiExportService exportService,
                          ObjectMapper objectMapper) {
        this.parser = parser;
        this.llmPlanner = llmPlanner;
        this.llmProperties = llmProperties;
        this.engine = engine;
        this.composer = composer;
        this.auditService = auditService;
        this.exportService = exportService;
        this.objectMapper = objectMapper;
    }

    public BiQueryResult ask(String question, BiPrincipal principal) {
        String traceId = newTraceId();
        long start = System.currentTimeMillis();
        BiQuerySpec spec = null;
        BiPlanInfo planInfo = BiPlanInfo.rule();
        try {
            PlanOutcome outcome = plan(question);
            spec = outcome.spec();
            planInfo = outcome.info();
            BiQueryResult result = engine.execute(spec, principal, traceId, planInfo);
            composer.compose(result);
            safeAuditSuccess(traceId, principal, spec, result, planInfo, System.currentTimeMillis() - start);
            return result;
        } catch (BiAccessDeniedException e) {
            safeAuditFailure(traceId, question, principal, spec, planInfo, "DENIED", e.getMessage(), start);
            throw e;
        } catch (RuntimeException e) {
            safeAuditFailure(traceId, question, principal, spec, planInfo, "FAILED", e.getMessage(), start);
            throw e;
        }
    }

    /** 只规划不执行：优先 LLM，失败按配置回退规则，返回本次规划来源与原始输出 */
    public PlanOutcome planOnly(String question) {
        return plan(question);
    }

    private PlanOutcome plan(String question) {
        if (llmPlanner.available()) {
            try {
                BiLlmPlanner.PlanResult result = llmPlanner.plan(question);
                return new PlanOutcome(result.spec(),
                        new BiPlanInfo("LLM", result.model(), null, result.rawOutput()));
            } catch (BiLlmException e) {
                if (!llmProperties.isFallbackToRule()) {
                    throw new BiServiceException("LLM 解析失败且未开启规则回退: " + e.getMessage(), e);
                }
                BiQuerySpec spec = parser.parse(question);
                return new PlanOutcome(spec,
                        new BiPlanInfo("RULE_FALLBACK", llmPlanner.model(), e.getMessage(), e.getRawOutput()));
            }
        }
        return new PlanOutcome(parser.parse(question), BiPlanInfo.rule());
    }

    public record PlanOutcome(BiQuerySpec spec, BiPlanInfo info) {
    }

    public CsvFile exportByQuestion(String question, BiPrincipal principal) {
        BiQueryResult result = ask(question, principal);
        auditService.markExported(result.getMeta().traceId());
        return new CsvFile(exportService.filename(result), exportService.toCsv(result));
    }

    public CsvFile exportByTrace(String traceId, BiPrincipal principal) {
        BiAuditLog log = auditService.find(traceId)
                .orElseThrow(() -> new BiServiceException("查询记录不存在: traceId=" + traceId));
        if (!principal.exportAllowed()) {
            throw new BiAccessDeniedException("当前角色不允许导出：" + principal.roleLabel());
        }
        if (!log.getUserId().equals(principal.userId()) && !principal.isAdmin()
                && !"ANALYST".equals(principal.role())) {
            throw new BiAccessDeniedException("不能导出其他用户的查询结果");
        }
        if ("BUSINESS_USER".equals(principal.role())
                && !principal.scopeLabel().equals(log.getScopeLabel())) {
            throw new BiAccessDeniedException("当前区域授权与查询时不一致，不能导出该快照");
        }
        if (!"SUCCESS".equals(log.getStatus())) {
            throw new BiServiceException("该 traceId 对应查询未成功，无法导出: " + log.getStatus());
        }
        if (log.getResultJson() == null || log.getResultJson().isBlank()) {
            throw new BiServiceException("该 traceId 未保存结果快照，无法导出");
        }
        try {
            BiQueryResult result = objectMapper.readValue(log.getResultJson(), BiQueryResult.class);
            auditService.markExported(traceId);
            return new CsvFile(exportService.filename(result), exportService.toCsv(result));
        } catch (BiServiceException | BiAccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            throw new BiServiceException("结果快照反序列化失败: " + e.getMessage(), e);
        }
    }

    private void safeAuditSuccess(String traceId, BiPrincipal principal, BiQuerySpec spec,
                                  BiQueryResult result, BiPlanInfo planInfo, long durationMs) {
        try {
            auditService.recordSuccess(traceId, principal, spec, result, planInfo, durationMs);
        } catch (RuntimeException e) {
            // 审计失败不应吞掉业务结果；生产应接监控告警
            log.warn("审计写入失败 traceId={}: {}", traceId, e.getMessage());
        }
    }

    private void safeAuditFailure(String traceId, String question, BiPrincipal principal,
                                  BiQuerySpec spec, BiPlanInfo planInfo, String status,
                                  String error, long start) {
        try {
            auditService.recordFailure(traceId, question, principal, spec, planInfo, status, error,
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            // 审计失败不应覆盖原始异常
            log.warn("失败审计写入失败 traceId={}: {}", traceId, e.getMessage());
        }
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record CsvFile(String filename, String content) {
    }
}
