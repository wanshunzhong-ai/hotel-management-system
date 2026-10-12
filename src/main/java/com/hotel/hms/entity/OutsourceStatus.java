package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 布草外送洗涤批次状态。
 *
 * <h3>与布草库存的联动</h3>
 * <ul>
 *     <li>{@link #SENT} 已送出：批次内各品名的数量从布草「在库」转入「洗涤中」；</li>
 *     <li>{@link #RETURNED} 已收回：从「洗涤中」转回「在库」；</li>
 *     <li>{@link #SETTLED} 已结算：与洗涤公司核对金额后收口，此后不再变动。</li>
 * </ul>
 * 结算必须在收回之后——衣服还没回来就付钱，账对不上。
 */
@Getter
public enum OutsourceStatus {

    SENT("已送出", "info"),
    RETURNED("已收回", "warning"),
    SETTLED("已结算", "success");

    private final String label;
    private final String color;

    OutsourceStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
