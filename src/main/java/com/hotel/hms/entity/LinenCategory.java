package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 布草品类。
 */
@Getter
public enum LinenCategory {

    BEDDING("床品"),
    TOWEL("巾类"),
    OTHER("其他");

    private final String label;

    LinenCategory(String label) {
        this.label = label;
    }
}
