package com.hotel.hms.repository;

import com.hotel.hms.entity.DutySchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DutyScheduleRepository extends JpaRepository<DutySchedule, Long> {

    List<DutySchedule> findByWorkDateOrderByShiftAscIdAsc(LocalDate workDate);

    List<DutySchedule> findByWorkDateBetweenOrderByWorkDateDescShiftAsc(LocalDate from, LocalDate to);

    boolean existsByWorkDateAndShiftAndStaffId(LocalDate workDate, com.hotel.hms.entity.DayShift shift, Long staffId);

    long countByWorkDate(LocalDate workDate);
}
