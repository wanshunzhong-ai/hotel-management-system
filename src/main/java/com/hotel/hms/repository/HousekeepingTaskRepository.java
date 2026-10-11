package com.hotel.hms.repository;

import com.hotel.hms.entity.HousekeepingTask;
import com.hotel.hms.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HousekeepingTaskRepository extends JpaRepository<HousekeepingTask, Long> {

    List<HousekeepingTask> findByStatusOrderByIdDesc(TaskStatus status);

    List<HousekeepingTask> findByAssigneeIdOrderByIdDesc(Long assigneeId);

    List<HousekeepingTask> findByStatusAndAssigneeIdOrderByIdDesc(TaskStatus status, Long assigneeId);

    long countByStatus(TaskStatus status);
}
