package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.config.BookingPolicy;
import com.hotel.hms.dto.ReservationForm;
import com.hotel.hms.entity.Customer;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 预订 / 入住 / 退房 业务编排。
 *
 * <h3>房态口径（第三批收敛后）</h3>
 * 本服务<b>不再写回</b> {@code room.status} 来标记「已被预订」。房间的物理状态只由
 * 入住（→ 已入住）、退房（→ 待清洁）、清洁完成（→ 空闲）与人工改房态驱动；
 * 某天能不能卖由预约表按日期区间实时计算。取消订单、删除订单、换房都<b>不需要</b>
 * 再动房态——这消除了旧实现里「取消一单就把房间置空闲、而该房隔几天还有别的单」的错乱。
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final RoomRepository roomRepository;
    private final CustomerService customerService;
    private final BillService billService;
    private final SerialNumberService serialNumberService;
    private final BookingPolicy bookingPolicy;
    private final OperationLogService operationLogService;

    /**
     * 预订单检索。
     *
     * @param checkInFrom 入住日期下限（含），可为 null
     * @param checkInTo   入住日期上限（含），可为 null
     * @param floor       楼层，可为 null
     */
    @Transactional(readOnly = true)
    public Page<Reservation> search(String keyword, ReservationStatus status,
                                    LocalDate checkInFrom, LocalDate checkInTo,
                                    Integer floor, Pageable pageable) {
        return reservationRepository.search(normalize(keyword), status, checkInFrom, checkInTo, floor, pageable);
    }

    @Transactional(readOnly = true)
    public Reservation getById(Long id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("预订单不存在"));
    }

    @Transactional(readOnly = true)
    public List<Reservation> findInHouse() {
        return reservationRepository.findInHouse();
    }

    @Transactional(readOnly = true)
    public List<Reservation> findArrivalsOn(LocalDate date) {
        return reservationRepository.findArrivalsOn(date);
    }

    @Transactional(readOnly = true)
    public List<Reservation> findDeparturesOn(LocalDate date) {
        return reservationRepository.findDeparturesOn(date);
    }

    /** 创建预订 */
    @Transactional
    public Reservation create(ReservationForm form, String operator) {
        validateDates(form.getCheckInDate(), form.getCheckOutDate(), null);

        // 先锁住房间再校验冲突：把「先查后写」串行化，避免两个前台同时下单造成超卖
        Room room = lockRoom(form.getRoomId());
        Customer customer = customerService.getById(form.getCustomerId());

        assertRoomUsable(room, form.getGuestCount());
        assertNoConflict(room.getId(), form.getCheckInDate(), form.getCheckOutDate(), null);

        Reservation reservation = new Reservation();
        reservation.setOrderNo(serialNumberService.nextOrderNo());
        reservation.setCustomer(customer);
        reservation.setRoom(room);
        reservation.setCheckInDate(form.getCheckInDate());
        reservation.setCheckOutDate(form.getCheckOutDate());
        reservation.setGuestCount(form.getGuestCount() == null ? 1 : form.getGuestCount());
        reservation.setStatus(ReservationStatus.BOOKED);
        reservation.setUnitPrice(room.getRoomType().getPrice());
        reservation.setOperator(operator);
        reservation.setRemark(form.getRemark());

        Reservation saved = reservationRepository.save(reservation);
        operationLogService.record("预订创建", "reservation", saved.getId(),
                saved.getOrderNo() + " / 房间 " + room.getRoomNo() + " / "
                        + form.getCheckInDate() + "→" + form.getCheckOutDate(), operator);
        return saved;
    }

    /** 修改预订（仅未入住可改） */
    @Transactional
    public Reservation update(Long id, ReservationForm form, String operator) {
        Reservation db = getById(id);
        if (db.getStatus() != ReservationStatus.BOOKED) {
            throw new BusinessException("只有「已预订」状态的订单可以修改");
        }
        // 改单时允许保留原有的入住日期（可能是已过期的未到店订单），但不允许新填过去日期
        validateDates(form.getCheckInDate(), form.getCheckOutDate(), db.getCheckInDate());

        Long oldRoomId = db.getRoom().getId();
        // 换房时涉及两把锁，按 id 升序获取，避免两个并发改单互相等待形成死锁
        Room room;
        if (oldRoomId.equals(form.getRoomId())) {
            room = lockRoom(oldRoomId);
        } else {
            lockRoom(Math.min(oldRoomId, form.getRoomId()));
            room = lockRoom(Math.max(oldRoomId, form.getRoomId()));
        }

        assertRoomUsable(room, form.getGuestCount());
        assertNoConflict(room.getId(), form.getCheckInDate(), form.getCheckOutDate(), db.getId());

        db.setCustomer(customerService.getById(form.getCustomerId()));
        db.setRoom(room);
        db.setCheckInDate(form.getCheckInDate());
        db.setCheckOutDate(form.getCheckOutDate());
        db.setActualCheckOutDate(null);
        db.setGuestCount(form.getGuestCount() == null ? 1 : form.getGuestCount());
        db.setUnitPrice(room.getRoomType().getPrice());
        db.setOperator(operator);
        db.setRemark(form.getRemark());
        Reservation saved = reservationRepository.save(db);
        operationLogService.record("预订修改", "reservation", saved.getId(),
                saved.getOrderNo() + " / 房间 " + room.getRoomNo() + " / "
                        + form.getCheckInDate() + "→" + form.getCheckOutDate(), operator);
        return saved;
    }

    /** 取消预订 */
    @Transactional
    public void cancel(Long id, String operator) {
        Reservation db = getById(id);
        if (!db.isCancelAllowed()) {
            throw new BusinessException("只有「已预订」状态的订单可以取消");
        }
        db.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(db);
        // 房态无需改写：该房这段时间能不能卖由预约表实时算出来
        operationLogService.record("预订取消", "reservation", db.getId(),
                db.getOrderNo() + " / 房间 " + db.getRoom().getRoomNo(), operator);
    }

    /** 办理入住 */
    @Transactional
    public Reservation checkIn(Long id, String operator) {
        Reservation db = getById(id);
        if (!db.isCheckInAllowed()) {
            throw new BusinessException("当前订单状态不允许办理入住");
        }
        Room room = lockRoom(db.getRoom().getId());
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            throw new BusinessException("房间正在维修，无法入住");
        }
        if (room.getStatus() == RoomStatus.OCCUPIED) {
            throw new BusinessException("房间已有人入住，请先核实");
        }
        // 房态可能因为人工操作而与实际不符，再以订单为准兜底一次
        if (reservationRepository.countInHouseByRoom(room.getId(), db.getId()) > 0) {
            throw new BusinessException("房间已有人入住，请先核实");
        }

        db.setStatus(ReservationStatus.CHECKED_IN);
        db.setCheckInTime(LocalDateTime.now());
        db.setOperator(operator);
        Reservation saved = reservationRepository.save(db);

        room.setStatus(RoomStatus.OCCUPIED);
        roomRepository.save(room);
        operationLogService.record("办理入住", "reservation", saved.getId(),
                saved.getOrderNo() + " / 房间 " + room.getRoomNo() + " / "
                        + saved.getCheckInDate() + "→" + saved.getCheckOutDate(), operator);
        return saved;
    }

    /** 办理退房（按今天为实际离店日，自动重算房费并生成账单） */
    @Transactional
    public Long checkOut(Long id, String operator) {
        return checkOut(id, LocalDate.now(), operator);
    }

    /**
     * 办理退房（第三批 §1.5）。
     *
     * <p>旧实现只写 {@code checkOutTime} 而不回写实际离店日，导致客人订 10/1–10/10、
     * 10/5 提前离店时账单仍按 9 晚收费，前台只能靠「追加消费填负数」变通。
     * 现在会写入 {@code actualCheckOutDate}，账单房费按<b>实际住夜数</b>（不足一天按一天）计算，
     * 差额作为「提前离店减免」记录，可审计。
     *
     * @param actualCheckOutDate 实际离店日期；为 {@code null} 时取今天
     */
    @Transactional
    public Long checkOut(Long id, LocalDate actualCheckOutDate, String operator) {
        Reservation db = getById(id);
        if (!db.isCheckOutAllowed()) {
            throw new BusinessException("只有「已入住」状态的订单可以办理退房");
        }
        LocalDate actual = actualCheckOutDate == null ? LocalDate.now() : actualCheckOutDate;
        if (actual.isBefore(db.getCheckInDate())) {
            throw new BusinessException("实际离店日期不能早于入住日期（" + db.getCheckInDate() + "）");
        }

        db.setStatus(ReservationStatus.CHECKED_OUT);
        db.setCheckOutTime(LocalDateTime.now());
        db.setActualCheckOutDate(actual);
        db.setOperator(operator);
        reservationRepository.save(db);

        Room room = lockRoom(db.getRoom().getId());
        room.setStatus(RoomStatus.CLEANING);
        roomRepository.save(room);

        Long billId = billService.createFromReservation(db.getId(), operator).getId();

        String detail = db.getOrderNo() + " / 房间 " + room.getRoomNo()
                + " / 实际住 " + db.getActualNightCount() + " 晚（计划 " + db.getNightCount() + " 晚）";
        if (db.getEarlyCheckoutDiscount().signum() > 0) {
            detail += " / 提前离店减免 ￥" + db.getEarlyCheckoutDiscount().toPlainString();
        }
        operationLogService.record("办理退房", "reservation", db.getId(), detail, operator);
        return billId;
    }

    /** 房间清洁完成 → 恢复空闲 */
    @Transactional
    public void markRoomClean(Long roomId, String operator) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("客房不存在"));
        if (room.getStatus() != RoomStatus.CLEANING) {
            throw new BusinessException("只有「待清洁」的房间可以确认清洁完成");
        }
        room.setStatus(RoomStatus.AVAILABLE);
        roomRepository.save(room);
        operationLogService.record("清洁完成", "room", room.getId(),
                "房间 " + room.getRoomNo() + " 待清洁 → 空闲", operator);
    }

    @Transactional
    public void delete(Long id, String operator) {
        Reservation db = getById(id);
        if (db.getStatus() == ReservationStatus.CHECKED_IN) {
            throw new BusinessException("订单正在住客，不能删除");
        }
        if (db.getStatus() == ReservationStatus.CHECKED_OUT) {
            throw new BusinessException("已完成的历史订单不能删除");
        }
        reservationRepository.delete(db);
        operationLogService.record("预订删除", "reservation", id,
                db.getOrderNo() + " / 房间 " + db.getRoom().getRoomNo(), operator);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 加行级排他锁读取房间（第三批 §1.4）。
     * <p>必须在事务内调用；锁在事务提交时释放。一致性靠它来保证，
     * 「查冲突 → 写订单」之间不会再有别的会话插入同一房间的重叠订单。
     */
    private Room lockRoom(Long roomId) {
        return roomRepository.findWithLockById(roomId)
                .orElseThrow(() -> new BusinessException("客房不存在"));
    }

    private void assertRoomUsable(Room room, Integer guestCount) {
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            throw new BusinessException("房间 " + room.getRoomNo() + " 正在维修，无法预订");
        }
        if (guestCount != null && room.getRoomType() != null
                && guestCount > room.getRoomType().getCapacity()) {
            throw new BusinessException("该房型最多入住 " + room.getRoomType().getCapacity() + " 人");
        }
    }

    /**
     * 校验预订日期。
     *
     * @param keepCheckIn 改单场景下允许沿用的原入住日期（可能已过期）；新建时传 null
     */
    private void validateDates(LocalDate checkIn, LocalDate checkOut, LocalDate keepCheckIn) {
        if (checkIn == null || checkOut == null) {
            throw new BusinessException("请选择入住与离店日期");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new BusinessException("离店日期必须晚于入住日期");
        }

        LocalDate today = LocalDate.now();
        if (checkIn.isBefore(today) && !checkIn.equals(keepCheckIn)) {
            throw new BusinessException("入住日期不能早于今天（" + today + "），如需补录历史订单请联系管理员");
        }
        if (!checkIn.isBefore(today)) {
            long advance = ChronoUnit.DAYS.between(today, checkIn);
            if (advance > bookingPolicy.getMaxAdvanceDays()) {
                throw new BusinessException("最多只能提前 " + bookingPolicy.getMaxAdvanceDays()
                        + " 天预订，最晚可订至 " + today.plusDays(bookingPolicy.getMaxAdvanceDays()));
            }
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > bookingPolicy.getMaxStayNights()) {
            throw new BusinessException("单次预订不能超过 " + bookingPolicy.getMaxStayNights() + " 晚");
        }
    }

    private void assertNoConflict(Long roomId, LocalDate checkIn, LocalDate checkOut, Long excludeId) {
        long conflicts = reservationRepository.countConflicts(roomId, checkIn, checkOut, excludeId);
        if (conflicts > 0) {
            throw new BusinessException("该房间在所选日期区间已被预订，请更换房间或日期");
        }
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
