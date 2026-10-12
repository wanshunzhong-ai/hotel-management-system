package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 系统角色。
 */
@Getter
public enum Role {

    ADMIN("管理员", "系统全部权限"),
    RECEPTION("前台接待", "预订、入住、退房、结账"),
    /**
     * 客房主管（第七批新增）。
     *
     * <p>权限在客房服务员之上：可派工、可验收、可维护排班与清洁标准；
     * 由 {@code SecurityConfig} 的 RoleHierarchy（{@code HOUSEKEEPING_SUPERVISOR > HOUSEKEEPING}）
     * 自动继承客房服务员的一切页面权限，{@code @PreAuthorize} 里只需额外声明主管本人。
     * 前台与资金类页面主管仍然进不去。
     */
    HOUSEKEEPING_SUPERVISOR("客房主管", "客房排班、派工与验收"),
    HOUSEKEEPING("客房服务", "房态查看与清洁维护");

    private final String label;
    private final String description;

    Role(String label, String description) {
        this.label = label;
        this.description = description;
    }
}
