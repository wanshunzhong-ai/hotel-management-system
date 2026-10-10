package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 客房服务。
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final RoomTypeService roomTypeService;

    @Transactional(readOnly = true)
    public List<Room> search(String keyword, RoomStatus status, Long roomTypeId, Integer floor) {
        return roomRepository.search(normalize(keyword), status, roomTypeId, floor);
    }

    @Transactional(readOnly = true)
    public List<Room> findAll() {
        return roomRepository.findAllByOrderByRoomNoAsc();
    }

    @Transactional(readOnly = true)
    public Room getById(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new BusinessException("客房不存在"));
    }

    @Transactional(readOnly = true)
    public List<Room> findAvailableBetween(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            return List.of();
        }
        return roomRepository.findAvailableBetween(checkIn, checkOut);
    }

    @Transactional(readOnly = true)
    public List<Integer> findAllFloors() {
        return roomRepository.findAll().stream()
                .map(Room::getFloor)
                .distinct()
                .sorted()
                .toList();
    }

    @Transactional
    public Room create(Room form) {
        if (roomRepository.existsByRoomNo(form.getRoomNo())) {
            throw new BusinessException("房号「" + form.getRoomNo() + "」已存在");
        }
        form.setId(null);
        if (form.getStatus() == null) {
            form.setStatus(RoomStatus.AVAILABLE);
        }
        // 重新加载受管的房型实体，避免前端只传 id 造成游离对象
        form.setRoomType(roomTypeService.getById(form.getRoomType().getId()));
        return roomRepository.save(form);
    }

    @Transactional
    public Room update(Long id, Room form) {
        Room db = getById(id);
        if (roomRepository.existsByRoomNoAndIdNot(form.getRoomNo(), id)) {
            throw new BusinessException("房号「" + form.getRoomNo() + "」已存在");
        }
        boolean hasActive = !reservationRepository
                .findByRoomIdAndStatusIn(id, List.of(ReservationStatus.BOOKED, ReservationStatus.CHECKED_IN))
                .isEmpty();
        if (hasActive && form.getStatus() != null && !form.getStatus().equals(db.getStatus())) {
            throw new BusinessException("该房间存在进行中的订单，不能直接修改房态，请先处理订单");
        }
        db.setRoomNo(form.getRoomNo());
        db.setFloor(form.getFloor());
        db.setRoomType(roomTypeService.getById(form.getRoomType().getId()));
        if (form.getStatus() != null) {
            db.setStatus(form.getStatus());
        }
        db.setRemark(form.getRemark());
        return roomRepository.save(db);
    }

    /** 单独调整房态（客房部日常操作） */
    @Transactional
    public void changeStatus(Long id, RoomStatus status) {
        Room db = getById(id);
        if (status == null) {
            throw new BusinessException("请选择房态");
        }
        if (db.getStatus() == RoomStatus.OCCUPIED && status != RoomStatus.CLEANING) {
            throw new BusinessException("在住房间需先办理退房才能变更房态");
        }
        List<?> active = reservationRepository
                .findByRoomIdAndStatusIn(id, List.of(ReservationStatus.BOOKED, ReservationStatus.CHECKED_IN));
        if (!active.isEmpty() && status == RoomStatus.MAINTENANCE) {
            throw new BusinessException("该房间存在进行中的订单，不能置为维修中");
        }
        db.setStatus(status);
        roomRepository.save(db);
    }

    @Transactional
    public void delete(Long id) {
        Room db = getById(id);
        if (db.getStatus() == RoomStatus.OCCUPIED) {
            throw new BusinessException("房间正在住客，不能删除");
        }
        if (!reservationRepository
                .findByRoomIdAndStatusIn(id, List.of(ReservationStatus.BOOKED, ReservationStatus.CHECKED_IN))
                .isEmpty()) {
            throw new BusinessException("该房间存在进行中的订单，不能删除");
        }
        roomRepository.delete(db);
    }

    private String normalize(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
