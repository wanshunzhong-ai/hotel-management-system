package com.hotel.hms.repository;

import com.hotel.hms.entity.ChecklistItem;
import com.hotel.hms.entity.ChecklistCategory;
import com.hotel.hms.entity.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {

    List<ChecklistItem> findAllByOrderByCategoryAscSortOrderAscIdAsc();

    List<ChecklistItem> findByEnabledTrueOrderByCategoryAscSortOrderAscIdAsc();

    boolean existsByItemName(String itemName);

    long countByEnabledTrue();

    /**
     * 生成执行清单时取适用的模板条目：启用中，且（未限定任务类型 或 正好匹配当前任务类型）。
     */
    @Query("""
            select c from ChecklistItem c
            where c.enabled = true
              and (c.taskType is null or c.taskType = :taskType)
            order by c.category asc, c.sortOrder asc, c.id asc
            """)
    List<ChecklistItem> findApplicable(TaskType taskType);

    List<ChecklistItem> findByCategoryOrderBySortOrderAscIdAsc(ChecklistCategory category);
}
