package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 维修工单状态。
 */
@Getter
public enum MaintenanceStatus {

    OPEN("待处理", "warning"),
    FIXING("维修中", "info"),
    DONE("已完工", "success"),
    CANCELLED("已取消", "secondary");

    private final String label;
    private final String color;

    MaintenanceStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
