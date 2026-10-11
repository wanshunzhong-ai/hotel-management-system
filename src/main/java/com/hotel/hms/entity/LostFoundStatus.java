package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 失物招领状态。
 */
@Getter
public enum LostFoundStatus {

    OPEN("待认领", "warning"),
    CLAIMED("已认领", "success");

    private final String label;
    private final String color;

    LostFoundStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
