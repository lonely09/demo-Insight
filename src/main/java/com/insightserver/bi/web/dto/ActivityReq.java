package com.insightserver.bi.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 活动新增/编辑请求 */
public record ActivityReq(
        @NotBlank(message = "活动名不能为空") @Size(max = 128) String name,
        @Size(max = 32) String type,
        @NotNull(message = "开始日期不能为空") LocalDate startDate,
        @NotNull(message = "结束日期不能为空") LocalDate endDate) {
}
