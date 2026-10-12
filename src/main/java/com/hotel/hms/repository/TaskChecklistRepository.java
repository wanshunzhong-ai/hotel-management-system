package com.hotel.hms.repository;

import com.hotel.hms.entity.TaskChecklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskChecklistRepository extends JpaRepository<TaskChecklist, Long> {

    List<TaskChecklist> findByTaskIdOrderByCategoryAscIdAsc(Long taskId);

    long countByTaskId(Long taskId);

    long countByTaskIdAndCheckedTrue(Long taskId);

    void deleteByTaskId(Long taskId);
}
