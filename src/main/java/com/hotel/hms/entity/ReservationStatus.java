package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 预订状态。
 */
@Getter
public enum ReservationStatus {

    BOOKED("已预订", "primary"),
    CHECKED_IN("已入住", "danger"),
    CHECKED_OUT("已退房", "success"),
    CANCELLED("已取消", "secondary");

    private final String label;
    private final String color;

    ReservationStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    /** 是否处于「进行中」状态（可办理入住 / 退房） */
    public boolean isActive() {
        return this == BOOKED || this == CHECKED_IN;
    }
}
