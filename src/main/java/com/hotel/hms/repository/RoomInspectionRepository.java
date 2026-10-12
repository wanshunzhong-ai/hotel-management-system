package com.hotel.hms.repository;

import com.hotel.hms.entity.InspectionResult;
import com.hotel.hms.entity.RoomInspection;
import com.hotel.hms.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomInspectionRepository extends JpaRepository<RoomInspection, Long> {

    List<RoomInspection> findByResultOrderByIdDesc(InspectionResult result);

    long countByResult(InspectionResult result);

    /** 平均分：放在 SQL 里聚合，不把整表捞进内存 */
    @Query("select avg(i.totalScore) from RoomInspection i")
    Double averageScore();

    /**
     * 待办中心：查出不合格、已自动派整改单、但整改单<b>尚未验收通过</b>的条数。
     * <p>用 {@code not exists} 子查询而不是先捞后比，是因为待办角标会在每个页面渲染时计算，
     * 必须是一次轻量查询。
     */
    @Query("""
            select count(i) from RoomInspection i
            where i.result = :fail
              and i.rectifiedTaskId is not null
              and not exists (select 1 from HousekeepingTask t
                              where t.id = i.rectifiedTaskId and t.status = :approved)
            """)
    long countPendingRectification(@Param("fail") InspectionResult fail, @Param("approved") TaskStatus approved);
}
