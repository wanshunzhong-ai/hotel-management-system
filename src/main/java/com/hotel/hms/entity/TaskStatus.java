package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 客房清洁任务状态：派工 → 完成 → 验收通过 / 打回重做。
 */
@Getter
public enum TaskStatus {

    PENDING("待完成", "warning"),
    DONE("待验收", "info"),
    APPROVED("验收通过", "success"),
    REJECTED("被打回", "danger");

    private final String label;
    private final String color;

    TaskStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
