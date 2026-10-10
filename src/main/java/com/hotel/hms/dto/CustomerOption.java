package com.hotel.hms.dto;

import com.hotel.hms.entity.Customer;

/**
 * 客户选择器（远程搜索下拉 / 内联新建）返回给前端的精简结构。
 *
 * <p>刻意不返回证件号等敏感字段：选择器只需要「认人」的信息，
 * 避免客户档案在网络响应里被整份带出去。
 */
public record CustomerOption(Long id, String name, String phone, String memberLevel, String gender) {

    public static CustomerOption of(Customer c) {
        if (c == null) {
            return null;
        }
        return new CustomerOption(
                c.getId(),
                c.getName(),
                c.getPhone(),
                c.getMemberLevel() == null || c.getMemberLevel().isBlank() ? "普通" : c.getMemberLevel(),
                c.getGender() == null ? "" : c.getGender().getLabel());
    }

    /** 展示文本：选择器输入框回填用 */
    public String display() {
        return name + " · " + phone;
    }
}
