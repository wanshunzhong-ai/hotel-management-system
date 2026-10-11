package com.hotel.hms.repository;

import com.hotel.hms.entity.Room;
import com.hotel.hms.entity.RoomStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    Optional<Room> findByRoomNo(String roomNo);

    /** 扫码页用：按二维码令牌定位房间 */
    Optional<Room> findByScanToken(String scanToken);

    boolean existsByScanToken(String scanToken);

    /**
     * 加行级排他锁读取房间（第三批 §1.4 并发保护）。
     *
     * <p>下单 / 改单 / 办理入住前先锁住目标房间，把「先查冲突、再写入」的
     * check-then-act 串行化。没有这个锁，两个前台同时给同一房间下同区间订单时，
     * 双方都读不到对方尚未提交的行，冲突校验都会通过，最终两单都落库 → 到店无房。
     *
     * <p><b>必须在事务内调用</b>，锁会在事务提交时释放。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findWithLockById(@Param("id") Long id);

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

    /**
     * 按房型统计房量，一次查询取代「逐个房型 count」的 N+1 调用。
     *
     * @return 每行为 {@code [roomTypeId, 房量]}
     */
    @Query("select r.roomType.id, count(r) from Room r group by r.roomType.id")
    List<Object[]> countGroupByRoomType();
}
