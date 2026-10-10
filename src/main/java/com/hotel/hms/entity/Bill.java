package com.hotel.hms.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
@Table(name = "bill", uniqueConstraints = @UniqueConstraint(name = "uk_bill_no", columnNames = "bill_no"))
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

    /** 房费合计 */
    @Column(name = "room_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal roomAmount = BigDecimal.ZERO;

    /** 其他消费合计（明细求和） */
    @Column(name = "other_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherAmount = BigDecimal.ZERO;

    /** 押金 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deposit = BigDecimal.ZERO;

    /** 应收合计 = 房费 + 其他消费 */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 实收金额（含押金抵扣） */
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

    /** 折扣/优惠后差额：实收 - 应收 */
    public BigDecimal getDifference() {
        return paidAmount.subtract(totalAmount);
    }
}
