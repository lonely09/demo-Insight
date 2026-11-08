package com.insightserver.bi.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 商品新增/编辑请求 */
public record ProductReq(
        @NotBlank(message = "商品名不能为空") @Size(max = 128) String name,
        @NotBlank(message = "sku 不能为空") @Size(max = 64) String sku,
        @Size(max = 32) String category,
        @NotNull(message = "价格不能为空") @DecimalMin(value = "0.01", message = "价格必须大于 0") BigDecimal price,
        String status) {
}
