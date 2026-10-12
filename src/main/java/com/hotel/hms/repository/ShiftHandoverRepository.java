package com.hotel.hms.repository;

import com.hotel.hms.entity.ShiftHandover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 交接班记录仓库（第六批）。
 */
@Repository
public interface ShiftHandoverRepository extends JpaRepository<ShiftHandover, Long> {

    boolean existsByShiftNo(String shiftNo);

    /** 上一次交班记录（用于确定本班统计区间起点） */
    Optional<ShiftHandover> findFirstByOrderByIdDesc();

    List<ShiftHandover> findTop30ByOrderByIdDesc();

    List<ShiftHandover> findByShiftDateBetweenOrderByIdDesc(LocalDate from, LocalDate to);
}
