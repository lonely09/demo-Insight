package com.example.demo.bi.service;

import java.math.BigDecimal;

/** 汇总结果条目 */
public class BiSummaryItem {

    private final Long dimId;
    private final String name;
    private final Long quantity;
    private final BigDecimal amount;

    public BiSummaryItem(Long dimId, String name, Long quantity, BigDecimal amount) {
        this.dimId = dimId;
        this.name = name;
        this.quantity = quantity;
        this.amount = amount;
    }

    public Long getDimId() { return dimId; }
    public String getName() { return name; }
    public Long getQuantity() { return quantity; }
    public BigDecimal getAmount() { return amount; }
}
