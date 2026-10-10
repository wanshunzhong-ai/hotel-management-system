package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 账单状态。
 */
@Getter
public enum BillStatus {

    UNPAID("待结账", "warning"),
    SETTLED("已结账", "success"),
    CANCELLED("已作废", "secondary");

    private final String label;
    private final String color;

    BillStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
