package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillItem;
import com.hotel.hms.entity.LaundryCategory;
import com.hotel.hms.entity.LaundryOrder;
import com.hotel.hms.entity.LaundryStatus;
import com.hotel.hms.entity.LinenStock;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.repository.BillRepository;
import com.hotel.hms.repository.LaundryOrderRepository;
import com.hotel.hms.repository.LinenStockRepository;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 洗衣服务：客衣 / 布草 收件 → 洗涤中 → 已送回。
 *
 * <p>客衣单计费，退房结账时由 {@link BillService#createFromReservation} 调用
 * {@link #mergeIntoBill} 并入账单「其他消费」；布草单不计费，送回时按品名回补库存。
 */
@Service
@RequiredArgsConstructor
public class LaundryService {

    /**
     * 结账时要收钱的客衣单状态。
     *
     * <p>注意包含 {@link LaundryStatus#RETURNED}——衣服已经洗好送回，钱当然还得收；
     * 只有「已取消」的单据不并入账单。写成「只并入未送回的单」会把已送回客衣漏收，
     * 这是写验收测试时才暴露出来的口径问题。
     */
    private static final List<LaundryStatus> CHARGEABLE_STATUSES =
            List.of(LaundryStatus.RECEIVED, LaundryStatus.WASHING, LaundryStatus.RETURNED);

    private final LaundryOrderRepository laundryOrderRepository;
    private final LinenStockRepository linenStockRepository;
    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final BillRepository billRepository;
    private final OperationLogService operationLogService;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LaundryOrder> list(LaundryStatus status) {
        if (status != null) {
            return laundryOrderRepository.findByStatusOrderByIdDesc(status);
        }
        return laundryOrderRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    public long countByStatus(LaundryStatus status) {
        return laundryOrderRepository.countByStatus(status);
    }

    /** 未结清客衣单的应收合计（结账页提示用） */
    @Transactional(readOnly = true)
    public BigDecimal pendingSum(Long reservationId) {
        if (reservationId == null) {
            return BigDecimal.ZERO;
        }
        return laundryOrderRepository
                .findByReservationIdAndStatusInOrderByIdAsc(reservationId, CHARGEABLE_STATUSES).stream()
                .filter(o -> o.getBillId() == null)
                .map(LaundryOrder::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------
    // 收件
    // ------------------------------------------------------------------

    /**
     * 收件登记。
     *
     * <p>客衣单必须挂在<b>在住订单</b>上（否则客人退房后无处收钱）；
     * 布草单只记送洗房间，可以留空。
     */
    @Transactional
    public Long create(LaundryCategory category, Long roomId, String itemName, Integer quantity,
                       BigDecimal unitPrice, String note, String operator) {
        LaundryCategory cat = category == null ? LaundryCategory.GUEST : category;
        if (itemName == null || itemName.isBlank()) {
            throw new BusinessException("请填写品名");
        }
        int qty = quantity == null || quantity < 1 ? 1 : quantity;

        LaundryOrder order = new LaundryOrder();
        order.setCategory(cat);
        order.setItemName(itemName.trim());
        order.setQuantity(qty);
        order.setNote(note);
        order.setOperator(operator);
        order.setStatus(LaundryStatus.RECEIVED);

        Room room = null;
        if (roomId != null) {
            room = roomRepository.findById(roomId)
                    .orElseThrow(() -> new BusinessException("房间不存在"));
        }
        order.setRoom(room);

        if (cat.isChargeable()) {
            if (room == null) {
                throw new BusinessException("客衣洗涤请选择房间");
            }
            BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
            if (price.signum() < 0) {
                throw new BusinessException("洗涤单价不能为负数");
            }
            List<Reservation> inHouse = reservationRepository
                    .findByRoomIdAndStatusIn(room.getId(), List.of(ReservationStatus.CHECKED_IN));
            if (inHouse.isEmpty()) {
                throw new BusinessException("该房间当前没有在住订单，客衣洗涤无法挂账");
            }
            order.setReservation(inHouse.get(0));
            order.setUnitPrice(price);
        } else {
            order.setUnitPrice(BigDecimal.ZERO);
        }
        order.recalculate();

        LaundryOrder saved = laundryOrderRepository.save(order);
        operationLogService.record("洗衣收件", "laundry_order", saved.getId(),
                (room == null ? "洗衣房" : room.getRoomNo()) + " · " + cat.getLabel() + " · "
                        + saved.getItemName() + " × " + qty
                        + (saved.isChargeable() ? " = ￥" + saved.getAmount() : "（内部周转）"),
                operator);
        return saved.getId();
    }

    // ------------------------------------------------------------------
    // 状态流转
    // ------------------------------------------------------------------

    @Transactional
    public void startWashing(Long id, String operator) {
        LaundryOrder order = get(id);
        if (order.getStatus() != LaundryStatus.RECEIVED) {
            throw new BusinessException("仅「已收件」的洗衣单可以开工洗涤");
        }
        order.setStatus(LaundryStatus.WASHING);
        laundryOrderRepository.save(order);
        operationLogService.record("洗衣洗涤", "laundry_order", id,
                displayRoom(order) + " · " + order.getItemName() + " 转入洗涤", operator);
    }

    /**
     * 送回：布草单按品名把数量从「洗涤中」回补到「在库」。
     */
    @Transactional
    public void markReturned(Long id, String operator) {
        LaundryOrder order = get(id);
        if (!order.getStatus().isOpen()) {
            throw new BusinessException("该洗衣单当前状态为「" + order.getStatus().getLabel() + "」，不能重复送回");
        }
        order.setStatus(LaundryStatus.RETURNED);
        order.setReturnedAt(LocalDateTime.now());
        laundryOrderRepository.save(order);

        String extra = "";
        if (order.getCategory() == LaundryCategory.LINEN) {
            extra = backToStock(order.getItemName(), order.getQuantity());
        }
        operationLogService.record("洗衣送回", "laundry_order", id,
                displayRoom(order) + " · " + order.getItemName() + " × " + order.getQuantity() + " 已送回" + extra,
                operator);
    }

    @Transactional
    public void cancel(Long id, String operator) {
        LaundryOrder order = get(id);
        if (!order.getStatus().isOpen()) {
            throw new BusinessException("该洗衣单当前状态为「" + order.getStatus().getLabel() + "」，不能取消");
        }
        order.setStatus(LaundryStatus.CANCELLED);
        laundryOrderRepository.save(order);
        operationLogService.record("洗衣取消", "laundry_order", id,
                displayRoom(order) + " · " + order.getItemName() + " 洗衣单已取消", operator);
    }

    // ------------------------------------------------------------------
    // 结账并入
    // ------------------------------------------------------------------

    /**
     * 把某订单的客衣洗涤费并入账单（仅未结账账单），返回并入金额。
     * 由 {@link BillService#createFromReservation} 在退房结账时调用，与业务同事务。
     *
     * <p>口径：客衣单只要没被取消，无论是否已送回都要收钱；
     * 幂等靠 {@code billId}——已并入过的单据不再重复计入。
     */
    @Transactional
    public BigDecimal mergeIntoBill(Reservation reservation, Bill bill, String operator) {
        List<LaundryOrder> open = laundryOrderRepository
                .findByReservationIdAndStatusInOrderByIdAsc(reservation.getId(), CHARGEABLE_STATUSES).stream()
                .filter(o -> o.getBillId() == null && o.isChargeable())
                .toList();
        if (open.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (LaundryOrder order : open) {
            BillItem billItem = new BillItem();
            billItem.setItemName("洗衣 · " + order.getItemName());
            billItem.setUnitPrice(order.getUnitPrice());
            billItem.setQuantity(order.getQuantity());
            billItem.setRemark("客衣洗涤自动并入");
            billItem.recalculate();
            bill.addItem(billItem);
            order.setBillId(bill.getId());
            laundryOrderRepository.save(order);
            sum = sum.add(order.getAmount());
        }
        BigDecimal other = bill.getOtherAmount() == null ? BigDecimal.ZERO : bill.getOtherAmount();
        BigDecimal discount = bill.getDiscountAmount() == null ? BigDecimal.ZERO : bill.getDiscountAmount();
        BigDecimal room = bill.getRoomAmount() == null ? BigDecimal.ZERO : bill.getRoomAmount();
        bill.setOtherAmount(other.add(sum));
        bill.setTotalAmount(room.add(other).add(sum).subtract(discount).max(BigDecimal.ZERO));
        billRepository.save(bill);
        operationLogService.record("洗衣并入账单", "bill", bill.getId(),
                "订单 " + reservation.getOrderNo() + " 并入 " + open.size() + " 条洗衣费，合计 ￥" + sum,
                operator);
        return sum;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private LaundryOrder get(Long id) {
        return laundryOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessException("洗衣单不存在"));
    }

    private String displayRoom(LaundryOrder order) {
        return order.getRoom() == null ? "洗衣房" : order.getRoom().getRoomNo();
    }

    /** 布草送回：洗涤中 → 在库。库存里没有这个品名就跳过（不报错）。 */
    private String backToStock(String itemName, Integer quantity) {
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        LinenStock stock = linenStockRepository.findByItemName(itemName).orElse(null);
        if (stock == null) {
            return "";
        }
        int inWash = stock.getInWash() == null ? 0 : stock.getInWash();
        stock.setInWash(Math.max(0, inWash - qty));
        stock.setInStock((stock.getInStock() == null ? 0 : stock.getInStock()) + qty);
        linenStockRepository.save(stock);
        return "，库存已回补 " + qty + " 件";
    }
}
