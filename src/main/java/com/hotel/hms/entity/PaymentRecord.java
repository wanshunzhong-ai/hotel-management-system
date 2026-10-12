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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收款流水（第六批）。
 *
 * <p>这是押金闭环与交接班对账的<b>唯一事实来源</b>：每一笔「客人给钱」「酒店退钱」
 * 都落一条记录，账单上的押金不再是前台手填的字段，而是本表按订单汇总出来的净额。
 *
 * <p>为什么要独立成表：先前押金只是 {@code bill.deposit} 一个手工字段，
 * 「什么时候收的、谁收的、什么方式」全都查不到；交接班要做当班收款汇总时无据可依。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_record",
        indexes = {
                // 按订单汇总预收款净额（结账时高频调用）
                @Index(name = "idx_payment_reservation", columnList = "reservation_id"),
                // 交接班按时间区间汇总
                @Index(name = "idx_payment_occurred_at", columnList = "occurred_at"),
                // 按类型汇总（押金 / 结账 / 预订金）
                @Index(name = "idx_payment_type", columnList = "type"),
                // 反结账时按账单清理结账类流水
                @Index(name = "idx_payment_bill", columnList = "bill_id")
        })
public class PaymentRecord extends BaseEntity {

    /** 所属订单（押金、预订金都挂在订单上） */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    /** 关联账单；只有「结账收款 / 找零」这两类在结账时才写入 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentType type;

    /** 金额，恒为正数；方向由 {@link PaymentType#isIncome()} 决定 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", nullable = false, length = 20)
    private PayMethod payMethod = PayMethod.CASH;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(length = 200)
    private String remark;

    /** 带符号金额：收钱为正、退钱为负 */
    public BigDecimal getSignedAmount() {
        return type == null ? BigDecimal.ZERO : type.signed(amount);
    }
}
