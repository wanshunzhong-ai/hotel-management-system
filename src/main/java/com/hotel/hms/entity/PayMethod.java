package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 支付方式。
 */
@Getter
public enum PayMethod {

    CASH("现金"),
    BANK_CARD("银行卡"),
    WECHAT("微信支付"),
    ALIPAY("支付宝"),
    OTHER("其他");

    private final String label;

    PayMethod(String label) {
        this.label = label;
    }
}
