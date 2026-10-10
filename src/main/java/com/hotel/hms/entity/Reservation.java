package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 预订单（同时承载入住 / 退房的生命周期）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "reservation", uniqueConstraints = @UniqueConstraint(name = "uk_reservation_order_no", columnNames = "order_no"))
public class Reservation extends BaseEntity {

    /** 订单号，如 RS20261010001 */
    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @NotNull(message = "请选择客户")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @NotNull(message = "请选择房间")
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /** 预计入住日期 */
    @NotNull(message = "请选择入住日期")
    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    /** 预计离店日期 */
    @NotNull(message = "请选择离店日期")
    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    /** 入住人数 */
    @Min(value = 1, message = "入住人数至少 1 人")
    @Column(name = "guest_count", nullable = false)
    private Integer guestCount = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.BOOKED;

    /** 实际入住时间 */
    @Column(name = "check_in_time")
    private LocalDateTime checkInTime;

    /** 实际退房时间 */
    @Column(name = "check_out_time")
    private LocalDateTime checkOutTime;

    /** 预订时锁定的房价（元/晚），避免后续调价影响历史订单 */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    /** 操作人姓名 */
    @Column(name = "operator", length = 50)
    private String operator;

    @Column(length = 200)
    private String remark;

    /** 住店晚数 */
    public long getNightCount() {
        if (checkInDate == null || checkOutDate == null) {
            return 0;
        }
        long nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate);
        return Math.max(nights, 0);
    }

    /** 房费小计 */
    public BigDecimal getRoomAmount() {
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(getNightCount()));
    }

    /** 是否可以办理入住 */
    public boolean isCheckInAllowed() {
        return status == ReservationStatus.BOOKED;
    }

    /** 是否可以办理退房 */
    public boolean isCheckOutAllowed() {
        return status == ReservationStatus.CHECKED_IN;
    }

    /** 是否可以取消 */
    public boolean isCancelAllowed() {
        return status == ReservationStatus.BOOKED;
    }
}
