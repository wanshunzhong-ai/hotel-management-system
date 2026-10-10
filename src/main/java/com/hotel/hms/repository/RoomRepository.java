package com.hotel.hms.repository;

import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findByRoomNo(String roomNo);

    boolean existsByRoomNo(String roomNo);

    boolean existsByRoomNoAndIdNot(String roomNo, Long id);

    List<Room> findAllByOrderByRoomNoAsc();

    List<Room> findByStatusOrderByRoomNoAsc(RoomStatus status);

    long countByStatus(RoomStatus status);

    boolean existsByRoomTypeId(Long roomTypeId);

    long countByRoomTypeId(Long roomTypeId);

    List<Room> findByRoomTypeIdOrderByRoomNoAsc(Long roomTypeId);

    @Query("""
            select r from Room r
            where (:keyword is null or r.roomNo like concat('%', :keyword, '%'))
              and (:status is null or r.status = :status)
              and (:roomTypeId is null or r.roomType.id = :roomTypeId)
              and (:floor is null or r.floor = :floor)
            order by r.roomNo asc
            """)
    List<Room> search(@Param("keyword") String keyword,
                      @Param("status") RoomStatus status,
                      @Param("roomTypeId") Long roomTypeId,
                      @Param("floor") Integer floor);

    /**
     * 查询在指定日期区间内没有被有效预订占用的房间。
     * 重叠判定：已有预订单的 [checkIn, checkOut) 与目标区间相交。
     */
    @Query("""
            select r from Room r
            where r.status <> com.hotel.hms.entity.RoomStatus.MAINTENANCE
              and not exists (
                    select 1 from Reservation v
                    where v.room = r
                      and v.status in (com.hotel.hms.entity.ReservationStatus.BOOKED,
                                       com.hotel.hms.entity.ReservationStatus.CHECKED_IN)
                      and v.checkInDate < :checkOut
                      and v.checkOutDate > :checkIn
              )
            order by r.roomNo asc
            """)
    List<Room> findAvailableBetween(@Param("checkIn") LocalDate checkIn,
                                    @Param("checkOut") LocalDate checkOut);
}
