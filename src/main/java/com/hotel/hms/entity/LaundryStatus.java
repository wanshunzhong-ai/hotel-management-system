package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 洗衣单状态：收件 → 洗涤中 → 已送回（或取消）。
 */
@Getter
public enum LaundryStatus {

    RECEIVED("已收件", "info"),
    WASHING("洗涤中", "warning"),
    RETURNED("已送回", "success"),
    CANCELLED("已取消", "secondary");

    private final String label;
    private final String color;

    LaundryStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    /** 是否仍在流转中（未送回、未取消） */
    public boolean isOpen() {
        return this == RECEIVED || this == WASHING;
    }
}
