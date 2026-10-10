package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.dto.BoardState;
import com.hotel.hms.dto.RoomBoardCell;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客房服务。
 *
 * <h3>房态口径（第三批 §1.1 收敛后）</h3>
 * <ul>
 *     <li>{@code room.status} = <b>物理状态</b>，只有空闲 / 已入住 / 待清洁 / 维修中四种；</li>
 *     <li>「某天能不能卖」= 由预约单按半开区间 {@code [checkIn, checkOut)} 实时计算，
 *         与 {@link RoomService#findAvailableBetween} 同一套口径；</li>
 *     <li>两者不再互相写回，因此不会出现「房态图说不可售、可订房查询说可售」的矛盾。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final RoomTypeService roomTypeService;
    private final OperationLogService operationLogService;

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

    // ------------------------------------------------------------------
    // 房态图（按日期）
    // ------------------------------------------------------------------

    /**
     * 计算某个日期（代表这一天<b>晚上</b>）全部房间的营业状态。
     *
     * @param date 目标日期，为 {@code null} 时取今天
     */
    @Transactional(readOnly = true)
    public List<RoomBoardCell> boardOn(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        boolean isToday = day.equals(LocalDate.now());

        // 一次取回该晚全部占用订单，避免逐房查询（40 房也不会产生 N+1）
        Map<Long, Reservation> occupancy = new HashMap<>();
        for (Reservation r : reservationRepository.findOccupyingOn(day)) {
            occupancy.putIfAbsent(r.getRoom().getId(), r);
        }

        List<Room> rooms = roomRepository.findAllByOrderByRoomNoAsc();
        List<RoomBoardCell> cells = new ArrayList<>(rooms.size());
        for (Room room : rooms) {
            Reservation occupied = occupancy.get(room.getId());
            cells.add(new RoomBoardCell(room, day, resolveState(room, occupied, day, isToday), occupied));
        }
        return cells;
    }

    /**
     * 判定单元格营业状态。
     *
     * <p>判定顺序刻意与 {@code findAvailableBetween} 对齐：先维修，再看该晚是否被占用。
     * 物理状态只在「查的是今天」时才参与判定——查未来某天时，今天是不是待清洁
     * 对那一天的销售没有意义。
     */
    private BoardState resolveState(Room room, Reservation occupied, LocalDate day, boolean isToday) {
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            return BoardState.MAINTENANCE;
        }
        if (occupied != null) {
            if (occupied.getStatus() == ReservationStatus.CHECKED_IN) {
                return BoardState.IN_HOUSE;
            }
            return occupied.getCheckInDate().isEqual(day) ? BoardState.ARRIVING : BoardState.RESERVED;
        }
        if (isToday) {
            if (room.getStatus() == RoomStatus.CLEANING) {
                return BoardState.CLEANING;
            }
            // 物理上还有人、但该晚没有订单 → 客人今天离店，房间今晚可再次出售
            if (room.getStatus() == RoomStatus.OCCUPIED) {
                return BoardState.DEPARTING;
            }
        }
        return BoardState.FREE;
    }

    /**
     * 可售房总量 = 总房量 − 维修房。
     * <p>出租率分母用这个值：长期维修的房间不应永久稀释出租率。
     * 维修是「此刻」的物理状态，未来日期的维修计划系统无法预知，故不随日期变化。
     */
    @Transactional(readOnly = true)
    public long countSellable() {
        return roomRepository.count() - roomRepository.countByStatus(RoomStatus.MAINTENANCE);
    }

    /** 某个日期已售房数（当晚有有效订单的房间数） */
    @Transactional(readOnly = true)
    public long countSoldOn(LocalDate date) {
        return reservationRepository.countOccupiedRoomsOn(date == null ? LocalDate.now() : date);
    }

    @Transactional
    public Room create(Room form) {
        if (roomRepository.existsByRoomNo(form.getRoomNo())) {
            throw new BusinessException("房号「" + form.getRoomNo() + "」已存在");
        }
        form.setId(null);
        if (form.getStatus() == null || !form.getStatus().isPhysical()) {
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
        boolean hasActive = reservationRepository.countActiveByRoom(id, null) > 0;
        if (hasActive && form.getStatus() != null && !form.getStatus().equals(db.getStatus())) {
            throw new BusinessException("该房间存在进行中的订单，不能直接修改房态，请先处理订单");
        }
        db.setRoomNo(form.getRoomNo());
        db.setFloor(form.getFloor());
        db.setRoomType(roomTypeService.getById(form.getRoomType().getId()));
        if (form.getStatus() != null && form.getStatus().isPhysical()) {
            db.setStatus(form.getStatus());
        }
        db.setRemark(form.getRemark());
        return roomRepository.save(db);
    }

    /** 单独调整房态（客房部日常操作） */
    @Transactional
    public void changeStatus(Long id, RoomStatus status, String operator) {
        Room db = getById(id);
        if (status == null || !status.isPhysical()) {
            throw new BusinessException("请选择房态");
        }
        if (db.getStatus() == RoomStatus.OCCUPIED && status != RoomStatus.CLEANING) {
            throw new BusinessException("在住房间需先办理退房才能变更房态");
        }
        if (status == RoomStatus.MAINTENANCE && reservationRepository.countActiveByRoom(id, null) > 0) {
            throw new BusinessException("该房间存在进行中的订单，不能置为维修中");
        }
        RoomStatus before = db.getStatus();
        db.setStatus(status);
        roomRepository.save(db);
        // 维修中会直接影响可售性，必须留痕
        operationLogService.record("房态变更", "room", db.getId(),
                "房间 " + db.getRoomNo() + "：" + before.getLabel() + " → " + status.getLabel(), operator);
    }

    @Transactional
    public void delete(Long id) {
        Room db = getById(id);
        if (db.getStatus() == RoomStatus.OCCUPIED) {
            throw new BusinessException("房间正在住客，不能删除");
        }
        if (reservationRepository.countActiveByRoom(id, null) > 0) {
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
