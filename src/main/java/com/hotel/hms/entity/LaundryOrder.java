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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 洗衣单：客衣 / 布草 收件 → 洗涤中 → 已送回。
 *
 * <h3>计费口径</h3>
 * <ul>
 *     <li>{@link LaundryCategory#GUEST} 客衣：必须挂在在住订单上，按「单价 × 件数」计费，
 *         退房结账时由 {@code LaundryService#mergeIntoBill} 并入账单「其他消费」；</li>
 *     <li>{@link LaundryCategory#LINEN} 布草：内部周转，金额恒为 0，不并入账单；
 *         送回时由 {@code LaundryService} 按品名把数量从「洗涤中」回补到「在库」。</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "laundry_order", indexes = {
        @Index(name = "idx_laundry_status", columnList = "status"),
        @Index(name = "idx_laundry_reservation", columnList = "reservation_id"),
        @Index(name = "idx_laundry_room", columnList = "room_id")
})
public class LaundryOrder extends BaseEntity {

    /** 客衣单必填；布草单为整批送洗，也记在送洗房间上 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "room_id")
    private Room room;

    /** 客衣单关联的在住订单；布草单为空 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LaundryCategory category = LaundryCategory.GUEST;

    /** 品名，如「衬衫」「大浴巾」 */
    @NotBlank(message = "请填写品名")
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(nullable = false)
    private Integer quantity = 1;

    /** 单价；布草类为内部周转，恒为 0 */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    /** 金额 = 单价 × 件数 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LaundryStatus status = LaundryStatus.RECEIVED;

    /** 已并入账单时回写账单 id，便于追溯 */
    @Column(name = "bill_id")
    private Long billId;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Size(max = 200)
    @Column(length = 200)
    private String note;

    /** 收件人 */
    @Column(length = 50)
    private String operator;

    public boolean isChargeable() {
        return category != null && category.isChargeable();
    }

    public void recalculate() {
        if (!isChargeable()) {
            this.amount = BigDecimal.ZERO;
            return;
        }
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        this.amount = price.multiply(BigDecimal.valueOf(qty));
    }
}
