package com.insightserver.bi.support;

/** 权限不足（区域数据范围越权、角色不允许导出等），统一映射为 HTTP 403 */
public class BiAccessDeniedException extends RuntimeException {

    public BiAccessDeniedException(String message) {
        super(message);
    }
}
