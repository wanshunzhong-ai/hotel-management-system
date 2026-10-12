package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.InspectionResult;
import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomInspection;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.entity.TaskType;
import com.hotel.hms.entity.User;
import com.hotel.hms.repository.ReservationRepository;
import com.hotel.hms.repository.RoomInspectionRepository;
import com.hotel.hms.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 查房评分服务。
 *
 * <p>评分本身只是记录，真正的价值在联动：<b>不合格自动生成整改派工单</b>，
 * 把「查出问题」和「派人返工」接起来，不需要主管再手工派一次。
 */
@Service
@RequiredArgsConstructor
public class InspectionService {

    private static final int MAX_PER_ITEM = 25;

    private final RoomInspectionRepository inspectionRepository;
    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;
    private final HousekeepingService housekeepingService;
    private final OperationLogService operationLogService;

    @Transactional(readOnly = true)
    public List<RoomInspection> list(InspectionResult result) {
        if (result != null) {
            return inspectionRepository.findByResultOrderByIdDesc(result);
        }
        return inspectionRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    @Transactional(readOnly = true)
    public long countByResult(InspectionResult result) {
        return inspectionRepository.countByResult(result);
    }

    /** 平均分（保留一位小数），无记录时返回 0 */
    @Transactional(readOnly = true)
    public double averageScore() {
        Double avg = inspectionRepository.averageScore();
        return avg == null ? 0d : Math.round(avg * 10) / 10d;
    }

    /**
     * 登记一次查房。
     *
     * @param assigneeId 不合格时要指派的整改服务员；为空则自动取第一位启用的客房服务员
     * @return 保存后的记录（{@code rectifiedTaskId} 非空表示已自动派工）
     */
    @Transactional
    public RoomInspection create(Long roomId, Integer hygiene, Integer facility, Integer linen,
                                 Integer amenity, String remark, Long assigneeId, String operator) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("房间不存在"));

        RoomInspection inspection = new RoomInspection();
        inspection.setRoom(room);
        inspection.setScoreHygiene(score(hygiene, "卫生"));
        inspection.setScoreFacility(score(facility, "设施"));
        inspection.setScoreLinen(score(linen, "布草"));
        inspection.setScoreAmenity(score(amenity, "客用品"));
        inspection.setRemark(remark);
        inspection.setInspector(operator);
        inspection.setInspectedAt(LocalDateTime.now());
        int total = inspection.recalculate();

        // 关联该房当前的在住订单（空房查房则为空）
        List<Reservation> inHouse = reservationRepository
                .findByRoomIdAndStatusIn(room.getId(), List.of(ReservationStatus.CHECKED_IN));
        if (!inHouse.isEmpty()) {
            inspection.setReservation(inHouse.get(0));
        }

        if (inspection.getResult() == InspectionResult.FAIL) {
            rectifiy(room, inspection, total, assigneeId, operator);
        }

        RoomInspection saved = inspectionRepository.save(inspection);
        operationLogService.record("查房评分", "room_inspection", saved.getId(),
                room.getRoomNo() + " 总分 " + total + "（" + saved.getGradeLabel() + "）"
                        + "，卫生" + saved.getScoreHygiene() + "/设施" + saved.getScoreFacility()
                        + "/布草" + saved.getScoreLinen() + "/客用品" + saved.getScoreAmenity()
                        + (saved.getRectifiedTaskId() == null ? "" : "，已派整改单 #" + saved.getRectifiedTaskId())
                        + (remark == null || remark.isBlank() ? "" : "（" + remark + "）"),
                operator);
        return saved;
    }

    // ------------------------------------------------------------------

    /**
     * 不合格的整改动作：房态置待清洁 + 自动派整改单。
     *
     * <p>在住（OCCUPIED）与维修中（MAINTENANCE）的房间<b>不</b>改房态——
     * 把有客人的房间标成待清洁会直接误导前台，而维修房本来就不可售。
     * 房态不允许改，但整改单仍然要派，两者互不依赖。
     */
    private void rectifiy(Room room, RoomInspection inspection, int total, Long assigneeId, String operator) {
        if (room.getStatus() == RoomStatus.AVAILABLE || room.getStatus() == RoomStatus.CLEANING) {
            room.setStatus(RoomStatus.CLEANING);
            roomRepository.save(room);
        }
        Long assignee = assigneeId != null ? assigneeId : firstStaffId();
        if (assignee == null) {
            // 一个客房服务员都没有：记录仍然保存，由主管手工派工
            return;
        }
        String taskRemark = "查房不合格自动整改（总分 " + total + "）"
                + (inspection.getRemark() == null || inspection.getRemark().isBlank()
                ? "" : "：" + inspection.getRemark());
        Long taskId = housekeepingService.create(room.getId(), TaskType.DEEP, assignee, taskRemark, operator);
        inspection.setRectifiedTaskId(taskId);
    }

    private Long firstStaffId() {
        List<User> staff = housekeepingService.listStaff();
        // 优先派给一线客房服务员；一个都没有时（如只留了主管）才落到主管身上
        return staff.stream().filter(u -> u.getRole() == Role.HOUSEKEEPING).findFirst()
                .or(() -> staff.stream().findFirst())
                .map(User::getId)
                .orElse(null);
    }

    private Integer score(Integer value, String field) {
        int v = value == null ? MAX_PER_ITEM : value;
        if (v < 0 || v > MAX_PER_ITEM) {
            throw new BusinessException(field + "得分需在 0–" + MAX_PER_ITEM + " 之间");
        }
        return v;
    }
}
