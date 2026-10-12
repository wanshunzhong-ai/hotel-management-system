package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发票开具记录（第六批）。
 *
 * <p>一张账单可以分次开票，但累计开票金额不得超过账单应收合计。
 * 开票只登记票面信息并留痕，不改变账单本身的状态。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "invoice",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoice_no", columnNames = "invoice_no"),
        indexes = {
                @Index(name = "idx_invoice_bill", columnList = "bill_id"),
                @Index(name = "idx_invoice_issued_at", columnList = "issued_at")
        })
public class Invoice extends BaseEntity {

    /** 发票号，如 IV202610110001 */
    @Column(name = "invoice_no", nullable = false, length = 32)
    private String invoiceNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceType type = InvoiceType.VAT_NORMAL;

    /** 发票抬头（单位名称或个人姓名） */
    @Column(nullable = false, length = 100)
    private String title;

    /** 纳税人识别号（专票必填） */
    @Column(name = "tax_no", length = 40)
    private String taxNo;

    /** 开票内容，如「住宿费」 */
    @Column(nullable = false, length = 100)
    private String content = "住宿费";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "buyer_phone", length = 30)
    private String buyerPhone;

    @Column(name = "buyer_address", length = 200)
    private String buyerAddress;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(length = 200)
    private String remark;

    /**
     * 是否作废。
     * <p>发票只登记不开具税控，作废用软删除（保留流水可审计），作废后金额不再计入该账单已开票额。
     */
    @Column(nullable = false)
    private Boolean cancelled = false;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;
}
