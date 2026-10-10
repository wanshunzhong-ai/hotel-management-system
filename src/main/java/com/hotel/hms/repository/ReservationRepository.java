package com.hotel.hms.repository;

import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    List<Reservation> findByStatusOrderByIdDesc(ReservationStatus status);

    List<Reservation> findByRoomIdAndStatusIn(Long roomId, List<ReservationStatus> statuses);

    long countByStatus(ReservationStatus status);

    /** 今日预抵（状态仍为已预订） */
    @Query("""
            select count(r) from Reservation r
            where r.checkInDate = :date and r.status = com.hotel.hms.entity.ReservationStatus.BOOKED
            """)
    long countArrivalsOn(@Param("date") LocalDate date);

    /** 今日预离（仍在住） */
    @Query("""
            select count(r) from Reservation r
            where r.checkOutDate = :date and r.status = com.hotel.hms.entity.ReservationStatus.CHECKED_IN
            """)
    long countDeparturesOn(@Param("date") LocalDate date);

    /** 今日实际入住 */
    @Query("""
            select count(r) from Reservation r
            where r.checkInTime >= :start and r.checkInTime < :end
            """)
    long countCheckedInBetween(@Param("start") java.time.LocalDateTime start,
                               @Param("end") java.time.LocalDateTime end);

    /** 在住订单 */
    @Query("""
            select r from Reservation r
            where r.status = com.hotel.hms.entity.ReservationStatus.CHECKED_IN
            order by r.checkInTime asc
            """)
    List<Reservation> findInHouse();

    /** 今日预抵明细 */
    @Query("""
            select r from Reservation r
            where r.checkInDate = :date
              and r.status in (com.hotel.hms.entity.ReservationStatus.BOOKED,
                               com.hotel.hms.entity.ReservationStatus.CHECKED_IN)
            order by r.id asc
            """)
    List<Reservation> findArrivalsOn(@Param("date") LocalDate date);

    /** 今日预离明细 */
    @Query("""
            select r from Reservation r
            where r.checkOutDate = :date
              and r.status = com.hotel.hms.entity.ReservationStatus.CHECKED_IN
            order by r.id asc
            """)
    List<Reservation> findDeparturesOn(@Param("date") LocalDate date);

    /** 日期重叠的有效预订数（用于房态冲突校验） */
    @Query("""
            select count(r) from Reservation r
            where r.room.id = :roomId
              and r.status in (com.hotel.hms.entity.ReservationStatus.BOOKED,
                               com.hotel.hms.entity.ReservationStatus.CHECKED_IN)
              and r.checkInDate < :checkOut
              and r.checkOutDate > :checkIn
              and (:excludeId is null or r.id <> :excludeId)
            """)
    long countConflicts(@Param("roomId") Long roomId,
                        @Param("checkIn") LocalDate checkIn,
                        @Param("checkOut") LocalDate checkOut,
                        @Param("excludeId") Long excludeId);

    @Query("""
            select r from Reservation r
            where (:keyword is null
                   or r.orderNo like concat('%', :keyword, '%')
                   or r.customer.name like concat('%', :keyword, '%')
                   or r.customer.phone like concat('%', :keyword, '%')
                   or r.room.roomNo like concat('%', :keyword, '%'))
              and (:status is null or r.status = :status)
            order by r.id desc
            """)
    List<Reservation> search(@Param("keyword") String keyword,
                             @Param("status") ReservationStatus status);
}
