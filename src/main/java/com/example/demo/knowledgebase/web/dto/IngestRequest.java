package com.example.demo.knowledgebase.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 文档入库请求 */
public record IngestRequest(
        @NotBlank(message = "title 不能为空") @Size(max = 256) String title,
        @NotBlank(message = "content 不能为空") String content,
        /** 来源标识：manual / file / url / 业务系统名 */
        String source) {
}
