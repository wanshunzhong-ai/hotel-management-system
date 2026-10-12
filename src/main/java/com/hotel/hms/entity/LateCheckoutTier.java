package com.hotel.hms.entity;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 延迟退房加收档位（第六批）。
 *
 * <p>行业惯例：12:00 前退房不加收，延时至 14:00 按半天房费加收，
 * 延时至 18:00 按全天房费加收；再往上由前台按 {@link #CUSTOM} 自行填写金额。
 * 这里把惯例固化成档位，前台不必手算。
 */
@Getter
public enum LateCheckoutTier {

    NONE("不延迟", 0, 0),
    HALF_DAY("延时至 14:00（半天房费）", 1, 2),
    FULL_DAY("延时至 18:00（全天房费）", 1, 1),
    CUSTOM("自定义加收", 0, 0);

    private final String label;
    /** 分子：加收房费的倍数分子 */
    private final int numerator;
    /** 分母：加收房费的倍数分母（半天 = 1/2） */
    private final int denominator;

    LateCheckoutTier(String label, int numerator, int denominator) {
        this.label = label;
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /**
     * 按档位计算加收金额。
     *
     * @param unitPrice 该订单锁定的房价（元/晚）
     * @return 加收金额；{@link #CUSTOM} 返回 0（由调用方自行传入金额）
     */
    public BigDecimal fee(BigDecimal unitPrice) {
        if (unitPrice == null || denominator == 0 || numerator == 0) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    /** 是否需要额外加收 */
    public boolean isChargeable() {
        return this != NONE;
    }
}
