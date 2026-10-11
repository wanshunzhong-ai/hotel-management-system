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
 * 账单与结算服务。
 *
 * <h3>金额口径（第三批统一后）</h3>
 * <pre>
 *   实际房费 roomAmount      = 房价 × 实际住夜数（提前离店已重算，见 §1.5）
 *   其他消费 otherAmount     = 消费明细求和
 *   人工折扣 discountAmount  = 协议价 / 抹零（§2.6）
 *   应收合计 totalAmount     = roomAmount + otherAmount − discountAmount
 *   结账门禁                 = 押金 + 本次收款 ≥ totalAmount
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class BillService {

    private final BillRepository billRepository;
    private final ReservationRepository reservationRepository;
    private final SerialNumberService serialNumberService;
    private final OperationLogService operationLogService;
    private final MinibarService minibarService;
    private final LaundryService laundryService;

    /**
     * 账单检索。
     *
     * @param settledFrom 结账日期下限（含），可为 null
     * @param settledTo   结账日期上限（含），可为 null；内部转成「次日 00:00」的开区间上界，
     *                    这样「结账日 = 今天」的账单不会被漏掉
     */
    @Transactional(readOnly = true)
    public Page<Bill> search(String keyword, BillStatus status,
                             LocalDate settledFrom, LocalDate settledTo, Pageable pageable) {
        LocalDateTime from = settledFrom == null ? null : settledFrom.atStartOfDay();
        LocalDateTime to = settledTo == null ? null : settledTo.plusDays(1).atStartOfDay();
        return billRepository.search(normalize(keyword), status, from, to, pageable);
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

    /**
     * 退房时自动生成账单。
     *
     * <p>房费取 {@link Reservation#getActualRoomAmount()}——提前离店时这里已经小于计划房费，
     * 差额写入 {@code earlyCheckoutDiscount} 供账单展示与追溯（不再参与应收合计的二次扣减）。
     */
    @Transactional
    public Bill createFromReservation(Long reservationId, String operator) {
        Bill bill = billRepository.findByReservationId(reservationId).orElseGet(() -> {
            Reservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new BusinessException("预订单不存在"));

            BigDecimal roomAmount = reservation.getActualRoomAmount();

            Bill created = new Bill();
            created.setBillNo(serialNumberService.nextBillNo());
            created.setReservation(reservation);
            created.setCustomer(reservation.getCustomer());
            created.setRoom(reservation.getRoom());
            created.setRoomAmount(roomAmount);
            created.setOtherAmount(BigDecimal.ZERO);
            created.setDiscountAmount(BigDecimal.ZERO);
            created.setEarlyCheckoutDiscount(reservation.getEarlyCheckoutDiscount());
            created.setTotalAmount(roomAmount);
            created.setDeposit(BigDecimal.ZERO);
            created.setPaidAmount(BigDecimal.ZERO);
            created.setRefundAmount(BigDecimal.ZERO);
            created.setStatus(BillStatus.UNPAID);
            created.setOperator(operator);
            return billRepository.save(created);
        });
        // 客房部的迷你吧 / 客用品挂账：退房结账时自动并入账单其他消费（同一事务）
        minibarService.mergeIntoBill(bill.getReservation(), bill, operator);
        // 客衣洗涤费同样在退房结账时并入（布草为内部周转，不计费）
        laundryService.mergeIntoBill(bill.getReservation(), bill, operator);
        return bill;
    }

    /** 追加消费明细 */
    @Transactional
    public Bill addItem(Long billId, BillItem item, String operator) {
        Bill bill = getDetail(billId);
        assertUnpaid(bill);
        if (item.getItemName() == null || item.getItemName().isBlank()) {
            throw new BusinessException("请填写消费项目名称");
        }
        item.setId(null);
        item.setQuantity(item.getQuantity() == null || item.getQuantity() < 1 ? 1 : item.getQuantity());
        item.setUnitPrice(item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice());
        // 第三批 §2.6：不再允许负单价。想减免请用「折扣」入口，否则无法审计。
        if (item.getUnitPrice().signum() < 0) {
            throw new BusinessException("消费单价不能为负数；如需优惠请使用「折扣 / 减免」功能");
        }
        item.recalculate();
        bill.addItem(item);
        recalculate(bill);
        billRepository.save(bill);
        operationLogService.record("追加消费", "bill", bill.getId(),
                bill.getBillNo() + " / " + item.getItemName() + " × " + item.getQuantity()
                        + " / 金额 ￥" + money(item.getAmount()), operator);
        return bill;
    }

    /** 删除消费明细 */
    @Transactional
    public Bill removeItem(Long billId, Long itemId, String operator) {
        Bill bill = getDetail(billId);
        assertUnpaid(bill);
        String removed = bill.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .map(i -> i.getItemName() + " / ￥" + money(i.getAmount()))
                .findFirst().orElse("未知项目");
        bill.getItems().removeIf(i -> i.getId().equals(itemId));
        recalculate(bill);
        billRepository.save(bill);
        operationLogService.record("删除消费", "bill", bill.getId(), bill.getBillNo() + " / " + removed, operator);
        return bill;
    }

    /**
     * 设置人工折扣 / 减免（第三批 §2.6）。
     *
     * @param discount 折扣金额，0 表示取消折扣；不能为负
     * @param reason   折扣原因，会写进账单备注与审计日志
     */
    @Transactional
    public Bill applyDiscount(Long billId, BigDecimal discount, String reason, String operator) {
        Bill bill = getDetail(billId);
        assertUnpaid(bill);

        BigDecimal value = discount == null ? BigDecimal.ZERO : discount;
        if (value.signum() < 0) {
            throw new BusinessException("折扣金额不能为负数");
        }
        BigDecimal before = nvl(bill.getDiscountAmount());
        // 注意：不能用 totalAmount < 0 来判断超额——recalculate() 会把合计钳到 0，
        // 负数判断永远不成立（曾导致 999999 的折扣被静默接受）。这里直接按上限比。
        BigDecimal cap = nvl(bill.getRoomAmount()).add(otherAmountOf(bill));
        if (value.compareTo(cap) > 0) {
            throw new BusinessException("折扣金额不能超过「房费 + 其他消费」（最多 ￥" + money(cap) + "）");
        }
        bill.setDiscountAmount(value);
        recalculate(bill);
        if (reason != null && !reason.isBlank()) {
            String merged = "折扣 " + money(value) + " 元：" + reason.trim();
            bill.setRemark(bill.getRemark() == null || bill.getRemark().isBlank()
                    ? merged
                    : bill.getRemark() + "；" + merged);
        }
        billRepository.save(bill);
        operationLogService.record("折扣减免", "bill", bill.getId(),
                bill.getBillNo() + " / 折扣 ￥" + money(value) + "（原 ￥" + money(before) + "）"
                        + (reason == null || reason.isBlank() ? "" : " / 原因：" + reason), operator);
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
        // 结账备注是「追加」而不是「覆盖」：账单备注里可能已经留了折扣原因、反结账原因，
        // 覆盖会把留痕抹掉（§2.6 的初衷就是让每笔调整都能对得上账）。
        if (form.getRemark() != null && !form.getRemark().isBlank()) {
            String note = form.getRemark().trim();
            bill.setRemark(bill.getRemark() == null || bill.getRemark().isBlank()
                    ? note
                    : bill.getRemark() + "；" + note);
        }
        Bill saved = billRepository.save(bill);
        operationLogService.record("结账", "bill", saved.getId(),
                saved.getBillNo() + " / 应收 ￥" + money(total) + " / 收款 ￥" + money(received)
                        + " / 应退 ￥" + money(saved.getRefundAmount())
                        + " / 方式 " + payMethod.getLabel(), operator);
        return saved;
    }

    /**
     * 反结账（第三批 §2.6）。
     *
     * <p>结错金额或付错方式时，把账单从「已结账」退回「待结账」以便修改后重结。
     * 原结算信息会留在审计日志里；账单上清空押金/收款/退款与支付方式，避免脏数据继续参与统计。
     *
     * <p>权限：仅 ADMIN（Controller 层已用 {@code @PreAuthorize} 限制）。
     */
    @Transactional
    public Bill unsettle(Long billId, String reason, String operator) {
        Bill bill = getDetail(billId);
        if (bill.getStatus() != BillStatus.SETTLED) {
            throw new BusinessException("只有「已结账」的账单可以反结账");
        }
        String summary = "原结算：押金 ￥" + money(bill.getDeposit())
                + " / 补付 ￥" + money(bill.getPaidAmount())
                + " / 应收 ￥" + money(bill.getTotalAmount())
                + " / 应退 ￥" + money(bill.getRefundAmount())
                + " / 方式 " + (bill.getPayMethod() == null ? "—" : bill.getPayMethod().getLabel())
                + " / 结账时间 " + (bill.getSettledAt() == null ? "—" : bill.getSettledAt());

        bill.setStatus(BillStatus.UNPAID);
        bill.setSettledAt(null);
        bill.setPayMethod(null);
        bill.setDeposit(BigDecimal.ZERO);
        bill.setPaidAmount(BigDecimal.ZERO);
        bill.setRefundAmount(BigDecimal.ZERO);
        String note = "反结账：" + (reason == null || reason.isBlank() ? "未填写原因" : reason.trim());
        bill.setRemark(bill.getRemark() == null || bill.getRemark().isBlank() ? note : bill.getRemark() + "；" + note);
        bill.setOperator(operator);
        Bill saved = billRepository.save(bill);

        operationLogService.record("反结账", "bill", saved.getId(),
                saved.getBillNo() + " / 原因：" + (reason == null || reason.isBlank() ? "未填写" : reason)
                        + " / " + summary, operator);
        return saved;
    }

    /** 重新计算合计：应收 = 房费 + 其他消费 − 折扣（不为负） */
    private void recalculate(Bill bill) {
        BigDecimal other = otherAmountOf(bill);
        bill.setOtherAmount(other);
        BigDecimal total = nvl(bill.getRoomAmount()).add(other).subtract(nvl(bill.getDiscountAmount()));
        bill.setTotalAmount(total.max(BigDecimal.ZERO));
    }

    /** 其他消费合计（= 明细金额之和） */
    private BigDecimal otherAmountOf(Bill bill) {
        return bill.getItems().stream()
                .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertUnpaid(Bill bill) {
        if (bill.getStatus() != BillStatus.UNPAID) {
            throw new BusinessException("账单已结账，不能再修改。如确需更正，请由管理员执行「反结账」。");
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 金额格式化：保留 2 位小数（仅用于提示文案） */
    private String money(BigDecimal value) {
        return nvl(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
