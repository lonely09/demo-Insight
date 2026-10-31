package com.example.demo.bi.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话 Agent 请求。身份与权限不放在 body 里，统一由请求头模拟：
 * X-Bi-User / X-Bi-Role / X-Bi-Region-Id，生产环境应由网关认证后注入。
 */
public record BiAgentReq(
        @NotBlank(message = "问题不能为空") @Size(max = 512, message = "问题最多 512 字符") String question) {
}
