package com.insightserver.knowledgebase.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 混合检索请求 */
public record SearchRequest(
        @NotBlank(message = "query 不能为空") String query,
        /** 可选：覆盖 kb.retrieval.final-top-k */
        Integer topK) {
}
