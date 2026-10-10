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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 预订 / 入住 / 退房 业务编排。
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

    @Transactional(readOnly = true)
    public List<Reservation> search(String keyword, ReservationStatus status) {
        return reservationRepository.search(normalize(keyword), status);
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

        Room room = roomRepository.findById(form.getRoomId())
                .orElseThrow(() -> new BusinessException("客房不存在"));
        Customer customer = customerService.getById(form.getCustomerId());

        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            throw new BusinessException("房间 " + room.getRoomNo() + " 正在维修，无法预订");
        }
        if (form.getGuestCount() != null && room.getRoomType() != null
                && form.getGuestCount() > room.getRoomType().getCapacity()) {
            throw new BusinessException("该房型最多入住 " + room.getRoomType().getCapacity() + " 人");
        }
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

        // 当日预订视为马上入住 → 房间转为已预订占位
        if (room.getStatus() == RoomStatus.AVAILABLE) {
            room.setStatus(RoomStatus.RESERVED);
            roomRepository.save(room);
        }
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

        Room room = roomRepository.findById(form.getRoomId())
                .orElseThrow(() -> new BusinessException("客房不存在"));
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            throw new BusinessException("房间 " + room.getRoomNo() + " 正在维修，无法预订");
        }
        if (form.getGuestCount() != null && room.getRoomType() != null
                && form.getGuestCount() > room.getRoomType().getCapacity()) {
            throw new BusinessException("该房型最多入住 " + room.getRoomType().getCapacity() + " 人");
        }
        assertNoConflict(room.getId(), form.getCheckInDate(), form.getCheckOutDate(), db.getId());

        Room oldRoom = db.getRoom();

        db.setCustomer(customerService.getById(form.getCustomerId()));
        db.setRoom(room);
        db.setCheckInDate(form.getCheckInDate());
        db.setCheckOutDate(form.getCheckOutDate());
        db.setGuestCount(form.getGuestCount() == null ? 1 : form.getGuestCount());
        db.setUnitPrice(room.getRoomType().getPrice());
        db.setOperator(operator);
        db.setRemark(form.getRemark());
        Reservation saved = reservationRepository.save(db);

        // 换房时释放原房间
        if (!oldRoom.getId().equals(room.getId())) {
            releaseRoom(oldRoom);
            if (room.getStatus() == RoomStatus.AVAILABLE) {
                room.setStatus(RoomStatus.RESERVED);
                roomRepository.save(room);
            }
        }
        return saved;
    }

    /** 取消预订 */
    @Transactional
    public void cancel(Long id) {
        Reservation db = getById(id);
        if (!db.isCancelAllowed()) {
            throw new BusinessException("只有「已预订」状态的订单可以取消");
        }
        db.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(db);
        releaseRoom(db.getRoom());
    }

    /** 办理入住 */
    @Transactional
    public Reservation checkIn(Long id, String operator) {
        Reservation db = getById(id);
        if (!db.isCheckInAllowed()) {
            throw new BusinessException("当前订单状态不允许办理入住");
        }
        Room room = db.getRoom();
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            throw new BusinessException("房间正在维修，无法入住");
        }
        if (room.getStatus() == RoomStatus.OCCUPIED) {
            throw new BusinessException("房间已有人入住，请先核实");
        }

        db.setStatus(ReservationStatus.CHECKED_IN);
        db.setCheckInTime(LocalDateTime.now());
        db.setOperator(operator);
        Reservation saved = reservationRepository.save(db);

        room.setStatus(RoomStatus.OCCUPIED);
        roomRepository.save(room);
        return saved;
    }

    /** 办理退房：置订单为已退房、房间转待清洁、自动生成账单 */
    @Transactional
    public Long checkOut(Long id, String operator) {
        Reservation db = getById(id);
        if (!db.isCheckOutAllowed()) {
            throw new BusinessException("只有「已入住」状态的订单可以办理退房");
        }
        db.setStatus(ReservationStatus.CHECKED_OUT);
        db.setCheckOutTime(LocalDateTime.now());
        db.setOperator(operator);
        reservationRepository.save(db);

        Room room = db.getRoom();
        room.setStatus(RoomStatus.CLEANING);
        roomRepository.save(room);

        return billService.createFromReservation(db.getId(), operator).getId();
    }

    /** 房间清洁完成 → 恢复空闲 */
    @Transactional
    public void markRoomClean(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("客房不存在"));
        if (room.getStatus() != RoomStatus.CLEANING) {
            throw new BusinessException("只有「待清洁」的房间可以确认清洁完成");
        }
        room.setStatus(RoomStatus.AVAILABLE);
        roomRepository.save(room);
    }

    @Transactional
    public void delete(Long id) {
        Reservation db = getById(id);
        if (db.getStatus() == ReservationStatus.CHECKED_IN) {
            throw new BusinessException("订单正在住客，不能删除");
        }
        if (db.getStatus() == ReservationStatus.CHECKED_OUT) {
            throw new BusinessException("已完成的历史订单不能删除");
        }
        reservationRepository.delete(db);
        if (db.getStatus() == ReservationStatus.BOOKED) {
            releaseRoom(db.getRoom());
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

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

    private void releaseRoom(Room room) {
        if (room == null) {
            return;
        }
        Room fresh = roomRepository.findById(room.getId()).orElse(null);
        if (fresh == null) {
            return;
        }
        if (fresh.getStatus() == RoomStatus.RESERVED) {
            fresh.setStatus(RoomStatus.AVAILABLE);
            roomRepository.save(fresh);
        }
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
