package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 客房消费项目分类。
 */
@Getter
public enum ConsumableCategory {

    MINIBAR("迷你吧"),
    SNACK("小食"),
    DAILY("客用品"),
    OTHER("其他");

    private final String label;

    ConsumableCategory(String label) {
        this.label = label;
    }
}
