package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 收款流水类型（第六批：押金闭环与交接班对账的基础）。
 *
 * <p>{@link #income} 标记资金方向：{@code true} = 酒店收到钱（正），
 * {@code false} = 酒店付出钱（负）。交接班对账按这个方向计算「当班净收款」。
 */
@Getter
public enum PaymentType {

    BOOKING_DEPOSIT("收取预订金", true, "bi-bookmark-plus"),
    DEPOSIT_IN("收取押金", true, "bi-shield-plus"),
    DEPOSIT_REFUND("退还押金", false, "bi-shield-minus"),
    SETTLE_PAY("结账收款", true, "bi-cash-coin"),
    SETTLE_REFUND("结账找零", false, "bi-arrow-return-left");

    private final String label;
    /** 是否属于「收到钱」；false 表示「付出钱」 */
    private final boolean income;
    private final String icon;

    PaymentType(String label, boolean income, String icon) {
        this.label = label;
        this.income = income;
        this.icon = icon;
    }

    /** 带符号的金额：付出记负数，便于直接求和 */
    public java.math.BigDecimal signed(java.math.BigDecimal amount) {
        if (amount == null) {
            return java.math.BigDecimal.ZERO;
        }
        return income ? amount : amount.negate();
    }

    /** 是否属于「预收款」（预订金 + 押金），退房结算时可抵扣应收 */
    public boolean isPrepayment() {
        return this == BOOKING_DEPOSIT || this == DEPOSIT_IN;
    }

    /** 是否属于「押金」范畴（收取或退还），供交接班分类汇总 */
    public boolean isDeposit() {
        return this == DEPOSIT_IN || this == DEPOSIT_REFUND;
    }

    /** 是否属于「结账」范畴（结账收款或找零） */
    public boolean isSettlement() {
        return this == SETTLE_PAY || this == SETTLE_REFUND;
    }
}
