package com.insightserver.bi.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 渠道新增/编辑请求 */
public record ChannelReq(
        @NotBlank(message = "渠道名不能为空") @Size(max = 128) String name,
        @NotBlank(message = "渠道编码不能为空") @Size(max = 64) String code,
        @Pattern(regexp = "ONLINE|OFFLINE", message = "类型只能是 ONLINE 或 OFFLINE") String type) {
}
