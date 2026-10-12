package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.entity.PaymentRecord;
import com.hotel.hms.entity.PaymentType;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.repository.PaymentRecordRepository;
import com.hotel.hms.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收款流水服务（第六批）。
 *
 * <h3>为什么要有这一层</h3>
 * 之前「押金」只是账单上的一个手工数字：什么时候收的、谁收的、用什么方式收的，
 * 系统一概不知；前台交班要做「当班收了多少现金」，只能靠脑子回忆或翻纸单。
 * 这里把每一次「客人给钱 / 酒店退钱」都落成一条流水，成为押金与交班的<b>唯一事实来源</b>。
 *
 * <h3>口径</h3>
 * <pre>
 *   预收款净额 prepaymentNet = （预订金 + 押金）− 已退押金
 *   可退押金余额 depositBalance = 已收押金 − 已退押金
 *   应收补付 = 账单应收 − 预收款净额
 * </pre>
 * 注意 {@link #prepaymentNet} 会把<b>预订金</b>也计入可抵扣额——预订金在结账时直接转为房费，
 * 这是行业通行做法；若不计入，客人付了预订金还要再付一遍全额。
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRecordRepository paymentRecordRepository;
    private final ReservationRepository reservationRepository;
    private final OperationLogService operationLogService;

    // ------------------------------------------------------------------ 押金 / 预订金

    /** 收取押金 */
    @Transactional
    public PaymentRecord depositIn(Long reservationId, BigDecimal amount, PayMethod method,
                                   String remark, String operator) {
        Reservation reservation = requireReservation(reservationId);
        assertChargeableStatus(reservation, "收取押金");
        return write(reservation, null, PaymentType.DEPOSIT_IN, amount, method, remark, operator);
    }

    /** 收取预订金 */
    @Transactional
    public PaymentRecord bookingDeposit(Long reservationId, BigDecimal amount, PayMethod method,
                                        String remark, String operator) {
        Reservation reservation = requireReservation(reservationId);
        if (reservation.getStatus() != ReservationStatus.BOOKED) {
            throw new BusinessException("只有「已预订」状态的订单可以收取预订金；"
                    + "订单当前状态为「" + reservation.getStatus().getLabel() + "」");
        }
        return write(reservation, null, PaymentType.BOOKING_DEPOSIT, amount, method, remark, operator);
    }

    /**
     * 退还押金。
     * <p>可退上限是<b>已收押金 − 已退押金</b>：退多了等于把酒店的钱退出去，
     * 系统直接拒绝。若客人押金在结账时已随账单一起退清，这里也会被正确拦住。
     */
    @Transactional
    public PaymentRecord depositRefund(Long reservationId, BigDecimal amount, PayMethod method,
                                       String remark, String operator) {
        Reservation reservation = requireReservation(reservationId);
        BigDecimal value = positive(amount, "退还押金");
        BigDecimal balance = depositBalance(reservationId);
        if (value.compareTo(balance) > 0) {
            throw new BusinessException("退还押金不能超过可退押金余额 ￥" + money(balance)
                    + "（已收 " + money(paymentRecordRepository.sumDepositIn(reservationId))
                    + " − 已退 " + money(paymentRecordRepository.sumDepositRefund(reservationId)) + "）");
        }
        return write(reservation, null, PaymentType.DEPOSIT_REFUND, value, method, remark, operator);
    }

    // ------------------------------------------------------------------ 结账收款（供 BillService 调用）

    /**
     * 登记退房结账的收款与退款流水。
     *
     * <p>退款遵循「先冲减押金、超出部分算找零」：押金本就是客人预交给酒店的钱，
     * 该退的当然先从它里面扣；只有「客人补付多了」才产生找零。
     * 这样流水的净额与「酒店实际还持有客人多少钱」始终一致。
     *
     * @param paid   客人补付金额（可能为 0）
     * @param refund 应退金额（可能为 0）
     */
    @Transactional
    public void recordSettlement(Bill bill, BigDecimal paid, BigDecimal refund,
                                 PayMethod method, String operator) {
        Reservation reservation = bill.getReservation();
        BigDecimal payValue = nvl(paid);
        BigDecimal refundValue = nvl(refund);

        if (payValue.signum() > 0) {
            paymentRecordRepository.save(build(reservation, bill, PaymentType.SETTLE_PAY,
                    payValue, method, "退房结账补付", operator));
        }
        if (refundValue.signum() > 0) {
            BigDecimal balance = depositBalance(reservation.getId());
            BigDecimal fromDeposit = refundValue.min(balance.max(BigDecimal.ZERO));
            BigDecimal change = refundValue.subtract(fromDeposit);
            if (fromDeposit.signum() > 0) {
                paymentRecordRepository.save(build(reservation, bill, PaymentType.DEPOSIT_REFUND,
                        fromDeposit, method, "退房结算退还押金", operator));
            }
            if (change.signum() > 0) {
                paymentRecordRepository.save(build(reservation, bill, PaymentType.SETTLE_REFUND,
                        change, method, "退房结算找零", operator));
            }
        }
    }

    /**
     * 反结账时清理结账产生的流水。
     * <p>只删挂在<b>该账单</b>上的流水（结账收款 / 找零 / 结算时退还的押金）；
     * 入住时正常收取的押金流水不在其列，会被保留——那笔钱确实收过。
     * 清理后 {@link #prepaymentNet} 自然回到结账前的数值，重新结账时押金自动恢复。
     */
    @Transactional
    public void clearSettlement(Long billId) {
        paymentRecordRepository.deleteByBillId(billId);
    }

    /**
     * 结账时把前台在表单里手工填写的押金补录进流水。
     *
     * <p>用途：账单押金以流水为准，但存量在住订单（系统上线前就入住的）没有押金流水。
     * 当前台填写的押金高于流水净额时，差额按「补录押金」入账，
     * 保证 {@code bill.deposit} 与流水永远对得上。
     *
     * @return 实际采用的押金（= 流水净额 + 补录额）
     */
    @Transactional
    public BigDecimal syncDepositAtSettle(Reservation reservation, BigDecimal formDeposit,
                                          PayMethod method, String operator) {
        BigDecimal ledger = prepaymentNet(reservation.getId());
        BigDecimal target = nvl(formDeposit);
        if (target.compareTo(ledger) > 0) {
            BigDecimal gap = target.subtract(ledger);
            paymentRecordRepository.save(build(reservation, null, PaymentType.DEPOSIT_IN,
                    gap, method, "结账时补录押金", operator));
            operationLogService.record("押金补录", "reservation", reservation.getId(),
                    reservation.getOrderNo() + " / 补录 ￥" + money(gap) + "（结账时账面押金 ￥"
                            + money(ledger) + "，表单填写 ￥" + money(target) + "）", operator);
            return target;
        }
        return ledger;
    }

    // ------------------------------------------------------------------ 查询

    /** 预收款净额（预订金 + 押金 − 已退押金）；结账时的可抵扣额 */
    @Transactional(readOnly = true)
    public BigDecimal prepaymentNet(Long reservationId) {
        return nvl(paymentRecordRepository.sumPrepaymentNet(reservationId));
    }

    /** 可退押金余额（已收押金 − 已退押金） */
    @Transactional(readOnly = true)
    public BigDecimal depositBalance(Long reservationId) {
        BigDecimal in = nvl(paymentRecordRepository.sumDepositIn(reservationId));
        BigDecimal out = nvl(paymentRecordRepository.sumDepositRefund(reservationId));
        return in.subtract(out);
    }

    @Transactional(readOnly = true)
    public List<PaymentRecord> listByReservation(Long reservationId) {
        return paymentRecordRepository.findByReservationIdOrderByIdDesc(reservationId);
    }

    @Transactional(readOnly = true)
    public List<PaymentRecord> listByBill(Long billId) {
        return paymentRecordRepository.findByBillIdOrderByIdAsc(billId);
    }

    @Transactional(readOnly = true)
    public Page<PaymentRecord> search(String keyword, PaymentType type, PayMethod method,
                                      LocalDate from, LocalDate to, Pageable pageable) {
        LocalDateTime start = from == null ? null : from.atStartOfDay();
        LocalDateTime end = to == null ? null : to.plusDays(1).atStartOfDay();
        return paymentRecordRepository.search(normalize(keyword), type, method, start, end, pageable);
    }

    // ------------------------------------------------------------------ 内部

    private PaymentRecord write(Reservation reservation, Bill bill, PaymentType type,
                                BigDecimal amount, PayMethod method, String remark, String operator) {
        BigDecimal value = positive(amount, type.getLabel());
        PaymentRecord record = build(reservation, bill, type, value, method, remark, operator);
        PaymentRecord saved = paymentRecordRepository.save(record);
        operationLogService.record(type.getLabel(), "payment_record", saved.getId(),
                reservation.getOrderNo() + " / " + type.getLabel() + " ￥" + money(value)
                        + " / 方式 " + methodLabel(method)
                        + (remark == null || remark.isBlank() ? "" : " / 备注：" + remark), operator);
        return saved;
    }

    private PaymentRecord build(Reservation reservation, Bill bill, PaymentType type,
                                BigDecimal amount, PayMethod method, String remark, String operator) {
        PaymentRecord record = new PaymentRecord();
        record.setReservation(reservation);
        record.setBill(bill);
        record.setType(type);
        record.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        record.setPayMethod(method == null ? PayMethod.CASH : method);
        record.setOccurredAt(LocalDateTime.now());
        record.setOperator(operator);
        record.setRemark(remark);
        return record;
    }

    private Reservation requireReservation(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException("预订单不存在"));
    }

    /** 押金只能在「已预订 / 已入住」阶段收取或退还 */
    private void assertChargeableStatus(Reservation reservation, String action) {
        ReservationStatus status = reservation.getStatus();
        if (status != ReservationStatus.BOOKED && status != ReservationStatus.CHECKED_IN) {
            throw new BusinessException(action + "失败：订单状态为「" + status.getLabel()
                    + "」，已离店或已取消的订单不再发生押金往来");
        }
    }

    private BigDecimal positive(BigDecimal amount, String action) {
        BigDecimal value = nvl(amount);
        if (value.signum() <= 0) {
            throw new BusinessException(action + "金额必须大于 0");
        }
        return value;
    }

    private String methodLabel(PayMethod method) {
        return method == null ? "—" : method.getLabel();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String money(BigDecimal value) {
        return nvl(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String normalize(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
