package com.example.demo.knowledgebase.web;

import com.example.demo.knowledgebase.support.KbServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class KbExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(KbExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("message", "参数校验失败");
        body.put("errors", e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()),
                        (a, b) -> a, LinkedHashMap::new)));
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(KbServiceException.class)
    public ResponseEntity<Map<String, Object>> onKbService(KbServiceException e) {
        log.warn("知识库服务异常: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 500);
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
