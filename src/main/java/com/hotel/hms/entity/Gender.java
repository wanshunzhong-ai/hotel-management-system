package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 性别。
 */
@Getter
public enum Gender {

    MALE("男"),
    FEMALE("女"),
    UNKNOWN("未知");

    private final String label;

    Gender(String label) {
        this.label = label;
    }
}
