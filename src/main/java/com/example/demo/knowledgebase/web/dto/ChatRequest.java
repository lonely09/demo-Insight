package com.example.demo.knowledgebase.web.dto;

import jakarta.validation.constraints.NotBlank;

/** RAG 问答请求 */
public record ChatRequest(
        @NotBlank(message = "question 不能为空") String question,
        Integer topK) {
}
