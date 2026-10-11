package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillItem;
import com.hotel.hms.entity.BillStatus;
import com.hotel.hms.entity.ChargeSource;
import com.hotel.hms.entity.MinibarItem;
import com.hotel.hms.entity.MinibarStatus;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.MinibarItemRepository;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 迷你吧 / 客用品挂账服务。
 *
 * <p>在住期间挂账（PENDING），退房结账时由
 * {@link BillService#createFromReservation} 调用 {@link #mergeIntoBill}
 * 自动并入账单「其他消费」并逐条生成 bill_item。
 */
@Service
@RequiredArgsConstructor
public class MinibarService {

    private final MinibarItemRepository minibarItemRepository;
    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final BillRepository billRepository;
    private final OperationLogService operationLogService;

    @Transactional(readOnly = true)
    public List<MinibarItem> list(MinibarStatus status) {
        if (status != null) {
            return minibarItemRepository.findByStatusOrderByIdDesc(status);
        }
        return minibarItemRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    public long countByStatus(MinibarStatus status) {
        return minibarItemRepository.countByStatus(status);
    }

    /** 在住挂账（前台录入） */
    @Transactional
    public Long create(Long roomId, String itemName, Integer quantity, BigDecimal unitPrice,
                       String note, String operator) {
        return create(roomId, itemName, quantity, unitPrice, note, operator, ChargeSource.MANUAL);
    }

    /**
     * 在住挂账（带来源）。
     *
     * @param source 录入来源；住客扫码走 {@link ChargeSource#SCAN}，前台录入为 {@link ChargeSource#MANUAL}
     */
    @Transactional
    public Long create(Long roomId, String itemName, Integer quantity, BigDecimal unitPrice,
                       String note, String operator, ChargeSource source) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("房间不存在"));
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("请填写品名");
        }
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        if (price.signum() < 0) {
            throw new BusinessException("挂账单价不能为负数");
        }
        List<Reservation> inHouse = reservationRepository
                .findByRoomIdAndStatusIn(roomId, List.of(ReservationStatus.CHECKED_IN));
        if (inHouse.isEmpty()) {
            throw new BusinessException("该房间当前没有在住订单，无法挂账");
        }
        MinibarItem item = new MinibarItem();
        item.setRoom(room);
        item.setReservation(inHouse.get(0));
        item.setItemName(itemName.trim());
        item.setQuantity(qty);
        item.setUnitPrice(price);
        item.recalculate();
        item.setStatus(MinibarStatus.PENDING);
        item.setNote(note);
        item.setOperator(operator);
        item.setSource(source == null ? ChargeSource.MANUAL : source);
        MinibarItem saved = minibarItemRepository.save(item);
        operationLogService.record("迷你吧挂账", "minibar_item", saved.getId(),
                room.getRoomNo() + " · " + saved.getItemName() + " × " + qty
                        + " = ￥" + saved.getAmount() + "（来源：" + saved.getSourceOrDefault().getLabel()
                        + "，订单 " + saved.getReservation().getOrderNo() + "）",
                operator);
        return saved.getId();
    }

    /** 取消未入账的挂账 */
    @Transactional
    public void delete(Long id, String operator) {
        MinibarItem item = minibarItemRepository.findById(id)
                .orElseThrow(() -> new BusinessException("挂账记录不存在"));
        if (item.getStatus() != MinibarStatus.PENDING) {
            throw new BusinessException("已并入账单的挂账不能删除");
        }
        minibarItemRepository.delete(item);
        operationLogService.record("迷你吧取消", "minibar_item", id,
                item.getRoom().getRoomNo() + " · " + item.getItemName() + " × " + item.getQuantity(), operator);
    }

    /**
     * 把某订单的待入账挂账并入账单（仅未结账账单），返回并入金额。
     * 由 BillService 在退房结账时调用，与业务同事务。
     */
    @Transactional
    public BigDecimal mergeIntoBill(Reservation reservation, Bill bill, String operator) {
        List<MinibarItem> pending = minibarItemRepository
                .findByReservationIdAndStatusOrderByIdAsc(reservation.getId(), MinibarStatus.PENDING);
        if (pending.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (MinibarItem item : pending) {
            BillItem billItem = new BillItem();
            billItem.setItemName("迷你吧 · " + item.getItemName());
            billItem.setUnitPrice(item.getUnitPrice());
            billItem.setQuantity(item.getQuantity());
            billItem.setRemark("客房挂账自动并入");
            billItem.recalculate();
            bill.addItem(billItem);
            item.setStatus(MinibarStatus.POSTED);
            item.setBillId(bill.getId());
            minibarItemRepository.save(item);
            sum = sum.add(item.getAmount());
        }
        BigDecimal other = bill.getOtherAmount() == null ? BigDecimal.ZERO : bill.getOtherAmount();
        BigDecimal discount = bill.getDiscountAmount() == null ? BigDecimal.ZERO : bill.getDiscountAmount();
        BigDecimal room = bill.getRoomAmount() == null ? BigDecimal.ZERO : bill.getRoomAmount();
        bill.setOtherAmount(other.add(sum));
        bill.setTotalAmount(room.add(other).add(sum).subtract(discount).max(BigDecimal.ZERO));
        billRepository.save(bill);
        operationLogService.record("迷你吧并入账单", "bill", bill.getId(),
                "订单 " + reservation.getOrderNo() + " 并入 " + pending.size() + " 条挂账，合计 ￥" + sum,
                operator);
        return sum;
    }

    /** 是否有未入账挂账（结账页提示用） */
    @Transactional(readOnly = true)
    public boolean hasPending(Long reservationId) {
        return !minibarItemRepository
                .findByReservationIdAndStatusOrderByIdAsc(reservationId, MinibarStatus.PENDING).isEmpty();
    }

    /** 未入账挂账合计 */
    @Transactional(readOnly = true)
    public BigDecimal pendingSum(Long reservationId) {
        return minibarItemRepository
                .findByReservationIdAndStatusOrderByIdAsc(reservationId, MinibarStatus.PENDING).stream()
                .map(MinibarItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
