package com.hotel.hms.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 结账表单。
 */
@Getter
@Setter
public class CheckoutForm {

    /** 押金 */
    private BigDecimal deposit = BigDecimal.ZERO;

    /** 本次收款金额 */
    private BigDecimal paidAmount = BigDecimal.ZERO;

    private String payMethod;

    private String remark;
}
