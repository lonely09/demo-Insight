package com.insightserver.bi.web;

import com.insightserver.bi.support.BiAccessDeniedException;
import com.insightserver.bi.support.BiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务数据模块异常处理 */
@RestControllerAdvice
public class BiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BiExceptionHandler.class);

    @ExceptionHandler(BiAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> onAccessDenied(BiAccessDeniedException e) {
        log.warn("业务数据权限校验失败: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 403);
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(BiServiceException.class)
    public ResponseEntity<Map<String, Object>> onBiService(BiServiceException e) {
        log.warn("业务数据异常: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> onDataIntegrity(DataIntegrityViolationException e) {
        log.warn("业务数据完整性冲突: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 409);
        body.put("message", "编码/SKU 重复或数据完整性冲突");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
