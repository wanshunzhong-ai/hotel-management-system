package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 迷你吧 / 客用品挂账状态：PENDING 在住挂账，退房结账时自动并入账单。
 */
@Getter
public enum MinibarStatus {

    PENDING("待入账", "warning"),
    POSTED("已入账单", "success");

    private final String label;
    private final String color;

    MinibarStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
