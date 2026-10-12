package com.hotel.hms.dto;

import com.hotel.hms.entity.PaymentRecord;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交接班对账汇总（第六批）。
 *
 * <p>统计区间是「上一次交班时刻 → 本次交班时刻」。首次交班（系统里还没有交班记录）时
 * 从当天 00:00 起算。
 */
@Getter
public class ShiftSummary {

    /** 统计区间起点（上一次交班时刻） */
    private final LocalDateTime fromTime;
    private final LocalDateTime toTime;
    /** 起点来源说明，如「上一班交班时间（SH202610110001）」或「今日 00:00（首次交班）」 */
    private final String fromLabel;

    /** 各支付方式的<b>净额</b>（收款 − 退款），键为支付方式名 */
    private final Map<String, BigDecimal> byMethod = new LinkedHashMap<>();

    /** 各类流水的合计金额，键为流水类型名 */
    private final Map<String, BigDecimal> byType = new LinkedHashMap<>();

    /** 押金 + 预订金收入合计 */
    private BigDecimal depositIn = BigDecimal.ZERO;
    /** 退还押金 + 结账找零合计（付出） */
    private BigDecimal refund = BigDecimal.ZERO;
    /** 结账收款合计 */
    private BigDecimal settle = BigDecimal.ZERO;
    /** 当班净收款 = 收入 − 付出 */
    private BigDecimal totalReceived = BigDecimal.ZERO;

    private long recordCount;
    private long orderCount;
    private long checkInCount;
    private long checkOutCount;

    /** 区间内的流水明细，供打印核对 */
    private List<PaymentRecord> records = List.of();

    public ShiftSummary(LocalDateTime fromTime, LocalDateTime toTime, String fromLabel) {
        this.fromTime = fromTime;
        this.toTime = toTime;
        this.fromLabel = fromLabel;
    }

    public void setRecords(List<PaymentRecord> records) {
        this.records = records;
    }

    public void setDepositIn(BigDecimal value) {
        this.depositIn = value;
    }

    public void setRefund(BigDecimal value) {
        this.refund = value;
    }

    public void setSettle(BigDecimal value) {
        this.settle = value;
    }

    public void setTotalReceived(BigDecimal value) {
        this.totalReceived = value;
    }

    public void setRecordCount(long value) {
        this.recordCount = value;
    }

    public void setOrderCount(long value) {
        this.orderCount = value;
    }

    public void setCheckInCount(long value) {
        this.checkInCount = value;
    }

    public void setCheckOutCount(long value) {
        this.checkOutCount = value;
    }

    /** 是否有任何资金往来 */
    public boolean isEmpty() {
        return recordCount == 0;
    }
}
