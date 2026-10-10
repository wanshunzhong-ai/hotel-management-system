package com.hotel.hms.repository;

import com.hotel.hms.entity.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {

    /** 已出现过的动作名，用于筛选下拉框 */
    @Query("select distinct l.action from OperationLog l order by l.action asc")
    List<String> distinctActions();

    /** 支持按日期区间 + 动作 + 关键词（动作/细节/操作人）筛选的分页查询 */
    @Query("""
            select l from OperationLog l
            where (:action is null or l.action = :action)
              and (:keyword is null
                   or l.detail like concat('%', :keyword, '%')
                   or l.operator like concat('%', :keyword, '%')
                   or l.action like concat('%', :keyword, '%'))
              and (:from is null or l.createdAt >= :from)
              and (:to is null or l.createdAt < :to)
            """)
    Page<OperationLog> search(@Param("action") String action,
                              @Param("keyword") String keyword,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              Pageable pageable);
}
