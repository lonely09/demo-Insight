package com.insightserver.bi.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 审计日志仓库：按 traceId 精确追溯，按用户/时间倒序浏览 */
public interface BiAuditLogRepository extends JpaRepository<BiAuditLog, Long> {

    Optional<BiAuditLog> findByTraceId(String traceId);

    List<BiAuditLog> findAllByOrderByIdDesc(Pageable pageable);

    List<BiAuditLog> findByUserIdOrderByIdDesc(String userId, Pageable pageable);
}
