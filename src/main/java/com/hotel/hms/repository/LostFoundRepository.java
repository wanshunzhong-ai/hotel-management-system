package com.hotel.hms.repository;

import com.hotel.hms.entity.LostFound;
import com.hotel.hms.entity.LostFoundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LostFoundRepository extends JpaRepository<LostFound, Long> {

    List<LostFound> findByStatusOrderByIdDesc(LostFoundStatus status);

    long countByStatus(LostFoundStatus status);

    /** 待办中心：登记超过 N 天仍无人认领的失物 */
    long countByStatusAndCreatedAtBefore(LostFoundStatus status, LocalDateTime before);
}
