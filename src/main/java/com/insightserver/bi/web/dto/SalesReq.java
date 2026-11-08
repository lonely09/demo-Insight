package com.insightserver.bi.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 销售流水新增请求 */
public record SalesReq(
        @NotNull(message = "销售日期不能为空") LocalDate statDate,
        @NotNull(message = "渠道不能为空") Long channelId,
        @NotNull(message = "商品不能为空") Long productId,
        @NotNull(message = "区域不能为空") Long regionId,
        Long activityId,
        @NotNull(message = "销量不能为空") @Min(value = 1, message = "销量至少为 1") Integer quantity,
        @NotNull(message = "成交额不能为空") @DecimalMin(value = "0.01", message = "成交额必须大于 0") BigDecimal amount) {
}