package com.hotel.hms.service;

import com.hotel.hms.common.BusinessException;
import com.hotel.hms.entity.HousekeepingTask;
import com.hotel.hms.entity.Role;
import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import com.hotel.hms.entity.TaskStatus;
import com.hotel.hms.entity.TaskType;
import com.hotel.hms.entity.User;
import com.hotel.hms.repository.HousekeepingTaskRepository;
import com.hotel.hms.repository.RoomRepository;
import com.hotel.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 客房派工服务：派工 → 服务员完成 → 主管验收通过（房间自动转「空闲」）/ 打回重做。
 */
@Service
@RequiredArgsConstructor
public class HousekeepingService {

    private final HousekeepingTaskRepository taskRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final OperationLogService operationLogService;

    @Transactional(readOnly = true)
    public List<HousekeepingTask> list(TaskStatus status, Long assigneeId) {
        if (status != null && assigneeId != null) {
            return taskRepository.findByStatusAndAssigneeIdOrderByIdDesc(status, assigneeId);
        }
        if (status != null) {
            return taskRepository.findByStatusOrderByIdDesc(status);
        }
        if (assigneeId != null) {
            return taskRepository.findByAssigneeIdOrderByIdDesc(assigneeId);
        }
        return taskRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    @Transactional(readOnly = true)
    public HousekeepingTask get(Long id) {
        return taskRepository.findById(id).orElseThrow(() -> new BusinessException("派工单不存在"));
    }

    public long countByStatus(TaskStatus status) {
        return taskRepository.countByStatus(status);
    }

    /** 客房服务员名单（派工下拉用） */
    @Transactional(readOnly = true)
    public List<User> listStaff() {
        return userRepository.findAllByOrderByIdAsc().stream()
                .filter(u -> u.getRole() == Role.HOUSEKEEPING && Boolean.TRUE.equals(u.getEnabled()))
                .toList();
    }

    /** 派工 */
    @Transactional
    public Long create(Long roomId, TaskType type, Long assigneeId, String remark, String operator) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException("房间不存在"));
        User assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> new BusinessException("服务员不存在"));
        if (assignee.getRole() != Role.HOUSEKEEPING || !Boolean.TRUE.equals(assignee.getEnabled())) {
            throw new BusinessException("只能派工给启用状态的客房服务员");
        }
        HousekeepingTask task = new HousekeepingTask();
        task.setRoom(room);
        task.setType(type == null ? TaskType.CHECKOUT : type);
        task.setStatus(TaskStatus.PENDING);
        task.setAssignee(assignee);
        task.setRemark(remark);
        HousekeepingTask saved = taskRepository.save(task);
        operationLogService.record("派工", "housekeeping_task", saved.getId(),
                saved.getRoom().getRoomNo() + " · " + saved.getType().getLabel()
                        + " → " + assignee.getRealName() + (remark == null || remark.isBlank() ? "" : "（" + remark + "）"),
                operator);
        return saved.getId();
    }

    /** 服务员标记完成（仅本人或管理员） */
    @Transactional
    public void markDone(Long id, Long userId, boolean isAdmin, String operator) {
        HousekeepingTask task = get(id);
        if (!isAdmin && (userId == null || !userId.equals(task.getAssignee().getId()))) {
            throw new BusinessException("只能标记自己负责的派工单");
        }
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.REJECTED) {
            throw new BusinessException("该派工单当前状态为「" + task.getStatus().getLabel() + "」，不能标记完成");
        }
        task.setStatus(TaskStatus.DONE);
        task.setDoneAt(LocalDateTime.now());
        operationLogService.record("清洁完成", "housekeeping_task", id,
                task.getRoom().getRoomNo() + " · " + task.getAssignee().getRealName() + " 标记完成，待验收", operator);
    }

    /** 验收通过：待清洁的房间自动转为空闲 */
    @Transactional
    public void approve(Long id, String inspectRemark, String inspector) {
        HousekeepingTask task = get(id);
        if (task.getStatus() != TaskStatus.DONE) {
            throw new BusinessException("仅「待验收」的派工单可以验收");
        }
        task.setStatus(TaskStatus.APPROVED);
        task.setInspector(inspector);
        task.setInspectedAt(LocalDateTime.now());
        task.setInspectRemark(inspectRemark);
        Room room = task.getRoom();
        if (room.getStatus() == RoomStatus.CLEANING) {
            room.setStatus(RoomStatus.AVAILABLE);
            roomRepository.save(room);
        }
        operationLogService.record("验收通过", "housekeeping_task", id,
                task.getRoom().getRoomNo() + " 验收通过"
                        + (room.getStatus() == RoomStatus.AVAILABLE ? "，房间已转空闲" : "")
                        + (inspectRemark == null || inspectRemark.isBlank() ? "" : "（" + inspectRemark + "）"),
                inspector);
    }

    /** 验收打回：服务员需重新完成后再次提交验收 */
    @Transactional
    public void reject(Long id, String inspectRemark, String inspector) {
        if (inspectRemark == null || inspectRemark.isBlank()) {
            throw new BusinessException("打回时必须填写打回原因");
        }
        HousekeepingTask task = get(id);
        if (task.getStatus() != TaskStatus.DONE) {
            throw new BusinessException("仅「待验收」的派工单可以打回");
        }
        task.setStatus(TaskStatus.REJECTED);
        task.setInspector(inspector);
        task.setInspectedAt(LocalDateTime.now());
        task.setInspectRemark(inspectRemark);
        operationLogService.record("验收打回", "housekeeping_task", id,
                task.getRoom().getRoomNo() + " 打回重做：" + inspectRemark, inspector);
    }
}
