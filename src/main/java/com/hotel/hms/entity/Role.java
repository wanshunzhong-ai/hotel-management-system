package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 系统角色。
 */
@Getter
public enum Role {

    ADMIN("管理员", "系统全部权限"),
    RECEPTION("前台接待", "预订、入住、退房、结账"),
    HOUSEKEEPING("客房服务", "房态查看与清洁维护");

    private final String label;
    private final String description;

    Role(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
