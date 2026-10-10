package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.dto.CheckoutForm;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillItem;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单与结算服务。
 */
@Service
@RequiredArgsConstructor
public class BillService {

    private final BillRepository billRepository;
    private final ReservationRepository reservationRepository;
    private final SerialNumberService serialNumberService;

    @Transactional(readOnly = true)
    public List<Bill> search(String keyword, BillStatus status) {
        return billRepository.search(normalize(keyword), status);
    }

    @Transactional(readOnly = true)
    public Bill getDetail(Long id) {
        return billRepository.findDetailById(id)
                .orElseThrow(() -> new BusinessException("账单不存在"));
    }

    @Transactional(readOnly = true)
    public Bill getByReservationId(Long reservationId) {
        return billRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new BusinessException("该订单还没有生成账单"));
    }

    /** 退房时自动生成账单 */
    @Transactional
    public Bill createFromReservation(Long reservationId, String operator) {
        return billRepository.findByReservationId(reservationId).orElseGet(() -> {
            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new BusinessException("预订单不存在"));

            Bill bill = new Bill();
            bill.setBillNo(serialNumberService.nextBillNo());
            bill.setReservation(reservation);
            bill.setCustomer(reservation.getCustomer());
            bill.setRoom(reservation.getRoom());
            bill.setRoomAmount(reservation.getRoomAmount());
            bill.setOtherAmount(BigDecimal.ZERO);
            bill.setDeposit(BigDecimal.ZERO);
            bill.setTotalAmount(reservation.getRoomAmount());
            bill.setPaidAmount(BigDecimal.ZERO);
            bill.setRefundAmount(BigDecimal.ZERO);
            bill.setStatus(BillStatus.UNPAID);
            bill.setOperator(operator);
            return billRepository.save(bill);
        });
    }

    /** 追加消费明细 */
    @Transactional
    public Bill addItem(Long billId, BillItem item) {
        Bill bill = getDetail(billId);
        assertUnpaid(bill);
        if (item.getItemName() == null || item.getItemName().isBlank()) {
            throw new BusinessException("请填写消费项目名称");
        }
        item.setId(null);
        item.setQuantity(item.getQuantity() == null || item.getQuantity() < 1 ? 1 : item.getQuantity());
        item.setUnitPrice(item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice());
        item.recalculate();
        bill.addItem(item);
        recalculate(bill);
        billRepository.save(bill);
        return bill;
    }

    /** 删除消费明细 */
    @Transactional
    public Bill removeItem(Long billId, Long itemId) {
        Bill bill = getDetail(billId);
        assertUnpaid(bill);
        bill.getItems().removeIf(i -> i.getId().equals(itemId));
        recalculate(bill);
        billRepository.save(bill);
        return bill;
    }

    /** 结账 */
    @Transactional
    public Bill settle(Long billId, CheckoutForm form, String operator) {
        Bill bill = getDetail(billId);
        assertUnpaid(bill);

        BigDecimal deposit = nvl(form.getDeposit());
        BigDecimal paid = nvl(form.getPaidAmount());
        if (deposit.signum() < 0 || paid.signum() < 0) {
            throw new BusinessException("押金与收款金额不能为负数");
        }
        if (form.getPayMethod() == null || form.getPayMethod().isBlank()) {
            throw new BusinessException("请选择支付方式");
        }
        PayMethod payMethod;
        try {
            payMethod = PayMethod.valueOf(form.getPayMethod());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("不支持的支付方式");
        }

        recalculate(bill);

        // 收款必须足额：押金 + 本次收款 >= 应收合计。否则拒绝结账，
        // 避免生成"已结账但仍欠款"且被 assertUnpaid 永久锁死的账单。
        BigDecimal received = deposit.add(paid);
        BigDecimal total = nvl(bill.getTotalAmount());
        if (received.compareTo(total) < 0) {
            throw new BusinessException("收款不足，无法结账：应收 ￥" + money(total)
                    + "，已收押金 ￥" + money(deposit) + " + 本次收款 ￥" + money(paid)
                    + " = ￥" + money(received) + "，还差 ￥" + money(total.subtract(received))
                    + "。如客人暂时无法付清，请先保留账单为「待结账」状态。");
        }

        bill.setDeposit(deposit);
        bill.setPaidAmount(paid);
        bill.setPayMethod(payMethod);
        bill.setRefundAmount(received.subtract(total));
        bill.setStatus(BillStatus.SETTLED);
        bill.setSettledAt(LocalDateTime.now());
        bill.setOperator(operator);
        bill.setRemark(form.getRemark());
        return billRepository.save(bill);
    }

    /** 重新计算合计 */
    private void recalculate(Bill bill) {
        BigDecimal other = bill.getItems().stream()
                .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        bill.setOtherAmount(other);
        bill.setTotalAmount(bill.getRoomAmount().add(other));
    }

    private void assertUnpaid(Bill bill) {
        if (bill.getStatus() != BillStatus.UNPAID) {
            throw new BusinessException("账单已结账，不能再修改");
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 金额格式化：保留 2 位小数（仅用于提示文案） */
    private String money(BigDecimal value) {
        return nvl(value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
