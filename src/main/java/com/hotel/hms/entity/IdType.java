package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 证件类型。
 */
@Getter
public enum IdType {

    ID_CARD("身份证"),
    PASSPORT("护照"),
    HK_MO_TW("港澳台通行证"),
    OTHER("其他证件");

    private final String label;

    IdType(String label) {
        this.label = label;
    }
}
