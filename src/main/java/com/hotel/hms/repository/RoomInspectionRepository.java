package com.hotel.hms.repository;

import com.hotel.hms.entity.InspectionResult;
import com.hotel.hms.entity.RoomInspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomInspectionRepository extends JpaRepository<RoomInspection, Long> {

    List<RoomInspection> findByResultOrderByIdDesc(InspectionResult result);

    long countByResult(InspectionResult result);

    /** 平均分：放在 SQL 里聚合，不把整表捞进内存 */
    @Query("select avg(i.totalScore) from RoomInspection i")
    Double averageScore();
}
