package com.hotel.hms.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 账单（退房结算）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bill",
        uniqueConstraints = @UniqueConstraint(name = "uk_bill_no", columnNames = "bill_no"),
        indexes = {
                // 未结账单计数、按状态筛选
                @Index(name = "idx_bill_status", columnList = "status"),
                // 看板营收统计（按结账时间过滤 / 分组）
                @Index(name = "idx_bill_settled_at", columnList = "settled_at")
        })
public class Bill extends BaseEntity {

    @Column(name = "bill_no", nullable = false, length = 32)
    private String billNo;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /** 房费合计（已按实际住夜数计算，提前离店时小于计划房费） */
    @Column(name = "room_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal roomAmount = BigDecimal.ZERO;

    /** 其他消费合计（明细求和） */
    @Column(name = "other_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherAmount = BigDecimal.ZERO;

    /**
     * 人工折扣 / 减免（第三批 §2.6）。
     * <p>协议价、抹零等优惠走这个字段，而不是「追加一笔负单价的消费」——
     * 后者无法审计，也容易被误当成退款。
     */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /**
     * 提前离店减免额（第三批 §1.5）。
     * <p><b>仅用于账单展示与审计</b>：{@link #roomAmount} 已经是按实际住夜数算好的金额，
     * 这个字段记录「计划房费 − 实际房费」的差额。它<b>不参与</b> {@link #totalAmount} 的二次扣减，
     * 否则会重复减免。
     */
    @Column(name = "early_checkout_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal earlyCheckoutDiscount = BigDecimal.ZERO;

    /** 入住时已收的押金（可用于抵扣应收） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deposit = BigDecimal.ZERO;

    /**
     * 延迟退房加收（第六批）。
     * <p>属于<b>房费侧</b>加收（不是消费明细），计入应收合计。
     * 为什么单独开字段而不是塞进消费明细：加收的性质是房费，混进「其他消费」会让
     * 房费口径（ADR 等）失真，也无法在报表里单独统计。
     */
    @Column(name = "late_checkout_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal lateCheckoutFee = BigDecimal.ZERO;

    /**
     * 挂账单位 / 应收账款方（第六批）。
     * <p>未结账单若由协议单位月结，在这里登记单位名称，配合 {@link #creditDueDate}
     * 构成应收账款台账；结清后账单转「已结账」，自然从台账中消失。
     */
    @Column(name = "credit_account", length = 100)
    private String creditAccount;

    /** 挂账到期日（约定回款日），用于账龄与逾期提醒 */
    @Column(name = "credit_due_date")
    private LocalDate creditDueDate;

    /** 应收合计 = 房费 + 其他消费 + 延迟退房加收 − 折扣 */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * 退房结算时客人<b>补付</b>的金额，<b>不含</b>入住时已收的押金。
     * <p>
     * 口径说明：本次结算的总收款 = {@code deposit + paidAmount}，见 {@link #getReceivedAmount()}；
     * 营收统计请使用 {@link #getTotalAmount()}（应收口径），不要用本字段，否则会漏掉押金抵扣部分。
     */
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** 找零 / 应退金额 */
    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", length = 20)
    private PayMethod payMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillStatus status = BillStatus.UNPAID;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(length = 200)
    private String remark;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BillItem> items = new ArrayList<>();

    public void addItem(BillItem item) {
        item.setBill(this);
        this.items.add(item);
    }

    /**
     * 结账时客人还差多少。
     * <p>正数 = 欠款，0 或负数 = 已付清或需找零。
     */
    public BigDecimal getDueAmount() {
        return totalAmount.subtract(deposit).subtract(paidAmount);
    }

    /**
     * 本次结算的总收款 = 押金 + 补付金额。
     * 结账后 {@code receivedAmount = totalAmount + refundAmount}。
     */
    public BigDecimal getReceivedAmount() {
        return deposit.add(paidAmount);
    }

    /** 是否发生过提前离店减免 */
    public boolean isEarlyCheckout() {
        return earlyCheckoutDiscount != null && earlyCheckoutDiscount.signum() > 0;
    }

    /** 是否有过折扣 */
    public boolean isDiscounted() {
        return discountAmount != null && discountAmount.signum() > 0;
    }

    /** 是否发生了延迟退房加收 */
    public boolean isLateCheckout() {
        return lateCheckoutFee != null && lateCheckoutFee.signum() > 0;
    }

    /** 是否已登记挂账单位（应收账款台账） */
    public boolean isCredited() {
        return creditAccount != null && !creditAccount.isBlank();
    }

    /**
     * 账龄：从退房日（未退房则从创建日）到今天的天数。
     * <p>用于应收账款台账的「挂了多少天」与逾期判断。
     */
    public long getAgingDays() {
        LocalDate base = reservation != null && reservation.getCheckOutTime() != null
                ? reservation.getCheckOutTime().toLocalDate()
                : (getCreatedAt() == null ? LocalDate.now() : getCreatedAt().toLocalDate());
        return java.time.temporal.ChronoUnit.DAYS.between(base, LocalDate.now());
    }

    /** 是否已逾期（仅当登记了到期日且未结账） */
    public boolean isOverdue() {
        return status == BillStatus.UNPAID && creditDueDate != null
                && creditDueDate.isBefore(LocalDate.now());
    }
}
