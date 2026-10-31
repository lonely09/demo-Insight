package com.example.demo.bi.support;

/** 业务数据领域异常（参数非法、维度不存在、存在关联流水不可删除等） */
public class BiServiceException extends RuntimeException {

    public BiServiceException(String message) {
        super(message);
    }

    public BiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
