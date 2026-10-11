package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 洗衣单类别。
 *
 * <p>两类单据走同一套状态机，但费用口径不同：
 * <ul>
 *     <li>{@link #GUEST} 客衣洗涤 —— 计费，退房结账时自动并入账单「其他消费」；</li>
 *     <li>{@link #LINEN} 布草洗涤 —— 内部周转，不计费；送回时按品名回补布草库存。</li>
 * </ul>
 */
@Getter
public enum LaundryCategory {

    GUEST("客衣", true),
    LINEN("布草", false);

    private final String label;
    /** 是否向客人计费（并入账单） */
    private final boolean chargeable;

    LaundryCategory(String label, boolean chargeable) {
        this.label = label;
        this.chargeable = chargeable;
    }
}
