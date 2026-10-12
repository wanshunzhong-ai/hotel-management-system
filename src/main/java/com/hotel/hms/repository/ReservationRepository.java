package com.hotel.hms.repository;

import com.hotel.hms.entity.Reservation;
import com.hotel.hms.entity.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** 区间内实际退房数（交接班核对工作量用） */
    @Query("""
            select count(r) from Reservation r
            where r.checkOutTime >= :start and r.checkOutTime < :end
            """)
    long countCheckedOutBetween(@Param("start") java.time.LocalDateTime start,
                                @Param("end") java.time.LocalDateTime end);

    /** 同一团名下的订单（团队批量入住） */
    @Query("""
            select r from Reservation r
            where r.groupName = :groupName
            order by r.room.roomNo asc
            """)
    List<Reservation> findByGroupName(@Param("groupName") String groupName);

    /** 已建团的团名清单（团队工作台的团名下拉） */
    @Query("""
            select distinct r.groupName from Reservation r
            where r.groupName is not null and r.groupName <> ''
            order by r.groupName asc
            """)
    List<String> findDistinctGroupNames();

    /** 指定日期入住、且尚未办理入住的订单（团队批量入住候选） */
    @Query("""
            select r from Reservation r
            where r.checkInDate = :date
              and r.status = com.hotel.hms.entity.ReservationStatus.BOOKED
            order by r.room.roomNo asc
            """)
    List<Reservation> findPendingArrivalsOn(@Param("date") LocalDate date);

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

    /**
     * 该晚（半开区间 {@code [date, date+1)}）占用客房的全部有效预订单。
     * <p>房态图按日期渲染时用它一次性取回全部占用信息，避免逐房查询。
     */
    @Query("""
            select r from Reservation r
            join fetch r.customer
            join fetch r.room
            where r.status in (com.hotel.hms.entity.ReservationStatus.BOOKED,
                               com.hotel.hms.entity.ReservationStatus.CHECKED_IN)
              and r.checkInDate <= :date
              and r.checkOutDate > :date
            order by r.room.roomNo asc
            """)
    List<Reservation> findOccupyingOn(@Param("date") LocalDate date);

    /**
     * 该晚已售房数（去重房间）。
     * <p>出租率口径：已售房 / 可售房，离店当天不占房（半开区间）。
     */
    @Query("""
            select count(distinct r.room.id) from Reservation r
            where r.status in (com.hotel.hms.entity.ReservationStatus.BOOKED,
                               com.hotel.hms.entity.ReservationStatus.CHECKED_IN)
              and r.checkInDate <= :date
              and r.checkOutDate > :date
            """)
    long countOccupiedRoomsOn(@Param("date") LocalDate date);

    /** 该房间是否还有其它有效订单（用于判断能否释放/删除） */
    @Query("""
            select count(r) from Reservation r
            where r.room.id = :roomId
              and r.status in (com.hotel.hms.entity.ReservationStatus.BOOKED,
                               com.hotel.hms.entity.ReservationStatus.CHECKED_IN)
              and (:excludeId is null or r.id <> :excludeId)
            """)
    long countActiveByRoom(@Param("roomId") Long roomId, @Param("excludeId") Long excludeId);

    /** 该房间当前的在住订单数（办理入住前的兜底校验） */
    @Query("""
            select count(r) from Reservation r
            where r.room.id = :roomId
              and r.status = com.hotel.hms.entity.ReservationStatus.CHECKED_IN
              and (:excludeId is null or r.id <> :excludeId)
            """)
    long countInHouseByRoom(@Param("roomId") Long roomId, @Param("excludeId") Long excludeId);

    /**
     * 预订单列表查询（分页 + 多条件筛选）。
     *
     * <p>排序不写死在这里，由调用方通过 {@link Pageable} 传入白名单内的 {@code Sort}，
     * 这样「最新创建 / 入住日期升降序 / 房号」可以在页面上切换。
     *
     * @param checkInFrom 入住日期下限（含），可为 null
     * @param checkInTo   入住日期上限（含），可为 null
     * @param floor       楼层，可为 null
     */
    @Query("""
            select r from Reservation r
            where (:keyword is null
                   or r.orderNo like concat('%', :keyword, '%')
                   or r.customer.name like concat('%', :keyword, '%')
                   or r.customer.phone like concat('%', :keyword, '%')
                   or r.room.roomNo like concat('%', :keyword, '%'))
              and (:status is null or r.status = :status)
              and (:checkInFrom is null or r.checkInDate >= :checkInFrom)
              and (:checkInTo is null or r.checkInDate <= :checkInTo)
              and (:floor is null or r.room.floor = :floor)
            """)
    Page<Reservation> search(@Param("keyword") String keyword,
                             @Param("status") ReservationStatus status,
                             @Param("checkInFrom") LocalDate checkInFrom,
                             @Param("checkInTo") LocalDate checkInTo,
                             @Param("floor") Integer floor,
                             Pageable pageable);
}
