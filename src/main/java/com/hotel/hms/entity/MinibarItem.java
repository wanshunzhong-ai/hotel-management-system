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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 迷你吧 / 客用品挂账：在住期间挂到房间，退房结账时自动并入账单「其他消费」。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "minibar_item", indexes = {
        @Index(name = "idx_minibar_status", columnList = "status"),
        @Index(name = "idx_minibar_reservation", columnList = "reservation_id")
})
public class MinibarItem extends BaseEntity {

    @NotNull(message = "请选择房间")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /** 挂账对应的在住订单 */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    /** 品名，如「矿泉水」「红酒」 */
    @NotBlank(message = "请填写品名")
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    /** 金额 = 单价 × 数量 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MinibarStatus status = MinibarStatus.PENDING;

    /** 入账后的账单 id（便于追溯） */
    @Column(name = "bill_id")
    private Long billId;

    @Size(max = 200)
    @Column(length = 200)
    private String note;

    /** 挂账操作人 */
    @Column(length = 50)
    private String operator;

    /**
     * 录入来源：前台录入 / 住客扫码。
     * <p>老数据该列为 NULL，展示时按「前台录入」兜底。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ChargeSource source = ChargeSource.MANUAL;

    /** 来源展示标签（NULL 兜底为前台录入） */
    public ChargeSource getSourceOrDefault() {
        return source == null ? ChargeSource.MANUAL : source;
    }

    public void recalculate() {
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        this.amount = price.multiply(BigDecimal.valueOf(qty));
    }
}
