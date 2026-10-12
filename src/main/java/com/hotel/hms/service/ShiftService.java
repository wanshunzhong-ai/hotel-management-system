package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.dto.ShiftSummary;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.entity.PaymentRecord;
import com.hotel.hms.entity.PaymentType;
import com.hotel.hms.entity.ShiftHandover;
import com.hotel.hms.repository.PaymentRecordRepository;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.ShiftHandoverRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 交接班对账服务（第六批）。
 *
 * <h3>要解决的问题</h3>
 * 前台换班时得说清楚「这一班收了多少现金、多少微信、退了几笔押金」，
 * 但之前系统里查不到任何按班次汇总的数据——只有一堆账单和一张打印名单。
 * 现在每一笔收款都落流水（见 {@link PaymentService}），交班时按「上次交班 → 现在」
 * 汇总成一份快照并落库，双方签字后不可篡改。
 */
@Service
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftHandoverRepository shiftHandoverRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final ReservationRepository reservationRepository;
    private final SerialNumberService serialNumberService;
    private final OperationLogService operationLogService;

    /**
     * 预览本班账目（不落库）。
     * <p>区间起点取「上一次交班时刻」；系统里还没有交班记录时从今天 00:00 起算。
     */
    @Transactional(readOnly = true)
    public ShiftSummary preview() {
        LocalDateTime to = LocalDateTime.now();
        Optional<ShiftHandover> last = shiftHandoverRepository.findFirstByOrderByIdDesc();
        LocalDateTime from;
        String fromLabel;
        if (last.isPresent()) {
            from = last.get().getToTime();
            fromLabel = "上一班交班时间（" + last.get().getShiftNo() + " " + last.get().getOperator() + "）";
        } else {
            from = LocalDate.now().atStartOfDay();
            fromLabel = "今日 00:00（系统内还没有交班记录）";
        }
        return build(from, to, fromLabel);
    }

    /** 按指定区间预览（供历史复核） */
    @Transactional(readOnly = true)
    public ShiftSummary build(LocalDateTime from, LocalDateTime to, String fromLabel) {
        ShiftSummary summary = new ShiftSummary(from, to, fromLabel);

        // 按「支付方式 × 流水类型」聚合，服务层再按支付方式汇总成净额
        for (Object[] row : paymentRecordRepository.summarizeBetween(from, to)) {
            PayMethod method = (PayMethod) row[0];
            PaymentType type = (PaymentType) row[1];
            BigDecimal amount = toMoney(row[2]);
            long count = ((Number) row[3]).longValue();

            String methodKey = method == null ? "未指定" : method.getLabel();
            BigDecimal net = summary.getByMethod().getOrDefault(methodKey, BigDecimal.ZERO)
                    .add(type.signed(amount));
            summary.getByMethod().put(methodKey, net);

            String typeKey = type.getLabel();
            summary.getByType().put(typeKey,
                    summary.getByType().getOrDefault(typeKey, BigDecimal.ZERO).add(amount));

            if (type == PaymentType.BOOKING_DEPOSIT || type == PaymentType.DEPOSIT_IN) {
                summary.setDepositIn(summary.getDepositIn().add(amount));
            } else if (type == PaymentType.DEPOSIT_REFUND || type == PaymentType.SETTLE_REFUND) {
                summary.setRefund(summary.getRefund().add(amount));
            } else if (type == PaymentType.SETTLE_PAY) {
                summary.setSettle(summary.getSettle().add(amount));
            }
            summary.setRecordCount(summary.getRecordCount() + count);
        }

        summary.setTotalReceived(summary.getDepositIn().subtract(summary.getRefund()).add(summary.getSettle()));
        summary.setOrderCount(paymentRecordRepository.countDistinctOrdersBetween(from, to));
        summary.setCheckInCount(reservationRepository.countCheckedInBetween(from, to));
        summary.setCheckOutCount(reservationRepository.countCheckedOutBetween(from, to));
        summary.setRecords(paymentRecordRepository.summarizeRecordsBetween(from, to));
        return summary;
    }

    /**
     * 确认交班：把当前班次账目固化成快照并落库。
     *
     * @param receivedBy   接班人姓名（可空，单人班次时填本人）
     * @param cashVerified 现金是否当面点清
     */
    @Transactional
    public ShiftHandover handover(String receivedBy, boolean cashVerified, String remark, String operator) {
        ShiftSummary summary = preview();
        if (summary.isEmpty()) {
            throw new BusinessException("本班没有任何资金往来，无需交班。"
                    + "区间：" + summary.getFromTime() + " ~ " + summary.getToTime());
        }

        ShiftHandover record = new ShiftHandover();
        record.setShiftNo(serialNumberService.nextShiftNo());
        record.setShiftDate(LocalDate.now());
        record.setOperator(operator);
        record.setReceivedBy(receivedBy == null || receivedBy.isBlank() ? operator : receivedBy.trim());
        record.setFromTime(summary.getFromTime());
        record.setToTime(summary.getToTime());

        record.setCashAmount(methodNet(summary, PayMethod.CASH));
        record.setWechatAmount(methodNet(summary, PayMethod.WECHAT));
        record.setAlipayAmount(methodNet(summary, PayMethod.ALIPAY));
        record.setBankCardAmount(methodNet(summary, PayMethod.BANK_CARD));
        record.setOtherAmount(methodNet(summary, PayMethod.OTHER));
        record.setDepositInAmount(summary.getDepositIn());
        record.setRefundAmount(summary.getRefund());
        record.setSettleAmount(summary.getSettle());
        record.setTotalReceived(summary.getTotalReceived());
        record.setOrderCount((int) summary.getOrderCount());
        record.setCheckInCount((int) summary.getCheckInCount());
        record.setCheckOutCount((int) summary.getCheckOutCount());
        record.setCashVerified(cashVerified);
        record.setRemark(remark == null || remark.isBlank() ? null : remark.trim());

        ShiftHandover saved = shiftHandoverRepository.save(record);
        operationLogService.record("交接班", "shift_handover", saved.getId(),
                saved.getShiftNo() + " / " + saved.getFromTime() + " ~ " + saved.getToTime()
                        + " / 净收款 ￥" + money(saved.getTotalReceived())
                        + "（现金 ￥" + money(saved.getCashAmount()) + "）"
                        + " / 办理入住 " + saved.getCheckInCount() + " 退房 " + saved.getCheckOutCount()
                        + " / 接班人 " + saved.getReceivedBy()
                        + (cashVerified ? " / 现金已当面点清" : " / 现金未点清"), operator);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ShiftHandover> history() {
        return shiftHandoverRepository.findTop30ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public ShiftHandover getById(Long id) {
        return shiftHandoverRepository.findById(id)
                .orElseThrow(() -> new BusinessException("交接班记录不存在"));
    }

    private BigDecimal methodNet(ShiftSummary summary, PayMethod method) {
        return summary.getByMethod().getOrDefault(method.getLabel(), BigDecimal.ZERO);
    }

    private BigDecimal toMoney(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).toPlainString();
    }
}
