package com.example.demo.knowledgebase.support;

/** 知识库领域异常（模型调用失败、参数非法、维度不匹配等） */
public class KbServiceException extends RuntimeException {

    public KbServiceException(String message) {
        super(message);
    }

    public KbServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
