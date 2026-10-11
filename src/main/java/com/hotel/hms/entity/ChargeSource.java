package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 客房消费记录的来源，用于区分录入渠道、便于事后审计。
 */
@Getter
public enum ChargeSource {

    MANUAL("前台录入", "secondary"),
    SCAN("住客扫码", "info");

    private final String label;
    private final String color;

    ChargeSource(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
