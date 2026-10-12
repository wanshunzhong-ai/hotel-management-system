package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 房价方案类型（第六批：协议价 / 会员价）。
 */
@Getter
public enum RatePlanType {

    MEMBER("会员价", "success"),
    CORPORATE("协议价", "primary"),
    PROMOTION("促销价", "warning"),
    OTHER("其他", "secondary");

    private final String label;
    private final String color;

    RatePlanType(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
