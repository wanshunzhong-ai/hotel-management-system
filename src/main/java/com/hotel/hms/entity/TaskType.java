package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 客房清洁任务类型。
 */
@Getter
public enum TaskType {

    CHECKOUT("走客房", "客人已退房，全面清扫"),
    STAYOVER("住客房", "在住客人的日常清扫"),
    DEEP("深度清洁", "周期性深度保洁");

    private final String label;
    private final String description;

    TaskType(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
