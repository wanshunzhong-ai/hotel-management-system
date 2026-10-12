package com.hotel.hms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交接班对账记录（第六批）。
 *
 * <p>交班时把「上一班结束 → 此刻」这段时间的收款按支付方式汇总成一份快照并落库，
 * 便于事后审计与打印签字。快照字段是<b>固化值</b>，不随流水变化。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "shift_handover",
        uniqueConstraints = @UniqueConstraint(name = "uk_shift_no", columnNames = "shift_no"),
        indexes = @Index(name = "idx_shift_date", columnList = "shift_date"))
public class ShiftHandover extends BaseEntity {

    /** 交接单号，如 SH202610110001 */
    @Column(name = "shift_no", nullable = false, length = 32)
    private String shiftNo;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    /** 交班人 */
    @Column(name = "operator", length = 50)
    private String operator;

    /** 接班人 */
    @Column(name = "received_by", length = 50)
    private String receivedBy;

    /** 统计区间起点（上一班交班时刻） */
    @Column(name = "from_time", nullable = false)
    private LocalDateTime fromTime;

    /** 统计区间终点（本次交班时刻） */
    @Column(name = "to_time", nullable = false)
    private LocalDateTime toTime;

    @Column(name = "cash_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashAmount = BigDecimal.ZERO;

    @Column(name = "wechat_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal wechatAmount = BigDecimal.ZERO;

    @Column(name = "alipay_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal alipayAmount = BigDecimal.ZERO;

    @Column(name = "bank_card_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal bankCardAmount = BigDecimal.ZERO;

    @Column(name = "other_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherAmount = BigDecimal.ZERO;

    /** 押金 + 预订金收入合计 */
    @Column(name = "deposit_in_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal depositInAmount = BigDecimal.ZERO;

    /** 退还押金 + 结账找零合计（付出） */
    @Column(name = "refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    /** 结账收款合计 */
    @Column(name = "settle_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal settleAmount = BigDecimal.ZERO;

    /** 当班净收款 = 收入合计 − 付出合计 */
    @Column(name = "total_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalReceived = BigDecimal.ZERO;

    /** 当班流水笔数 */
    @Column(name = "order_count", nullable = false)
    private Integer orderCount = 0;

    /** 当班办理入住 / 退房数（便于交接核对工作量） */
    @Column(name = "check_in_count", nullable = false)
    private Integer checkInCount = 0;

    @Column(name = "check_out_count", nullable = false)
    private Integer checkOutCount = 0;

    @Column(length = 200)
    private String remark;

    /** 现金是否已当面点清并交接 */
    @Column(name = "cash_verified", nullable = false)
    private Boolean cashVerified = false;
}
