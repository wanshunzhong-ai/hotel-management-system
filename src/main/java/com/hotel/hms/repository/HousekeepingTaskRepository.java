package com.hotel.hms.repository;

import com.hotel.hms.entity.HousekeepingTask;
import com.hotel.hms.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface HousekeepingTaskRepository extends JpaRepository<HousekeepingTask, Long> {

    List<HousekeepingTask> findByStatusOrderByIdDesc(TaskStatus status);

    List<HousekeepingTask> findByAssigneeIdOrderByIdDesc(Long assigneeId);

    List<HousekeepingTask> findByStatusAndAssigneeIdOrderByIdDesc(TaskStatus status, Long assigneeId);

    long countByStatus(TaskStatus status);

    // ---------------- 第七批：待办中心 / 排班工作量 ----------------

    /** 「我的待办」：派给我的、还没走完的单（待完成 / 被打回） */
    List<HousekeepingTask> findByAssigneeIdAndStatusInOrderByIdDesc(Long assigneeId, Collection<TaskStatus> statuses);

    /** 超时未完成：仍是待完成、且派出时间早于阈值 */
    List<HousekeepingTask> findByStatusAndCreatedAtBeforeOrderByIdAsc(TaskStatus status, LocalDateTime before);

    /** 超时待验收：已标记完成、但迟迟没人验收 */
    List<HousekeepingTask> findByStatusAndDoneAtBeforeOrderByIdAsc(TaskStatus status, LocalDateTime before);

    /** 工作量统计：取某个时间段内派出的全部工单，在应用层按人聚合（数据量小，不做脆弱的 HQL 时间差函数） */
    List<HousekeepingTask> findByCreatedAtBetweenOrderByIdAsc(LocalDateTime from, LocalDateTime to);

    /** 某房间尚未走完的整改单（查房不合格联动用） */
    List<HousekeepingTask> findByRoomIdAndStatusInOrderByIdDesc(Long roomId, Collection<TaskStatus> statuses);

    long countByAssigneeIdAndStatus(Long assigneeId, TaskStatus status);
}
