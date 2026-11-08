package com.insightserver.bi.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 区域新增/编辑请求 */
public record RegionReq(
        @NotBlank(message = "区域名不能为空") @Size(max = 128) String name,
        @NotBlank(message = "区域编码不能为空") @Size(max = 64) String code,
        @Min(value = 1, message = "层级最小为 1") @Max(value = 3, message = "层级最大为 3") Integer level,
        @Size(max = 32) String status,
        Long parentId) {
}
