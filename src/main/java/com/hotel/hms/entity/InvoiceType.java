package com.hotel.hms.entity;

import lombok.Getter;

/**
 * 发票类型。
 */
@Getter
public enum InvoiceType {

    VAT_NORMAL("增值税普通发票", "普票"),
    VAT_SPECIAL("增值税专用发票", "专票");

    private final String label;
    /** 票面简称，用于列表窄列展示 */
    private final String shortLabel;

    InvoiceType(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }
}
