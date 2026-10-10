package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 房间状态。
 */
@Getter
public enum RoomStatus {

    AVAILABLE("空闲", "success", "bi-door-open"),
    RESERVED("已预订", "info", "bi-bookmark-check"),
    OCCUPIED("已入住", "danger", "bi-person-fill"),
    CLEANING("待清洁", "warning", "bi-stars"),
    MAINTENANCE("维修中", "secondary", "bi-tools");

    private final String label;
    /** Bootstrap 色板后缀 */
    private final String color;
    private final String icon;

    RoomStatus(String label, String color, String icon) {
        this.label = label;
        this.color = color;
        this.icon = icon;
    }

    /** 是否可被预订 */
    public boolean isBookable() {
        return this == AVAILABLE;
    }
}
