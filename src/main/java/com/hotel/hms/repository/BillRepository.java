package com.hotel.hms.repository;

import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillRepository extends JpaRepository<Bill, Long> {

    Optional<Bill> findByBillNo(String billNo);

    boolean existsByBillNo(String billNo);

    Optional<Bill> findByReservationId(Long reservationId);

    long countByStatus(BillStatus status);

    List<Bill> findByStatusAndSettledAtBetween(BillStatus status, LocalDateTime from, LocalDateTime to);

    /** 详情查询，一次性抓取消费明细 */
    @Query("select distinct b from Bill b left join fetch b.items where b.id = :id")
    Optional<Bill> findDetailById(@Param("id") Long id);

    @Query("""
            select b from Bill b
            where (:keyword is null
                   or b.billNo like concat('%', :keyword, '%')
                   or b.customer.name like concat('%', :keyword, '%')
                   or b.room.roomNo like concat('%', :keyword, '%'))
              and (:status is null or b.status = :status)
            order by b.id desc
            """)
    List<Bill> search(@Param("keyword") String keyword, @Param("status") BillStatus status);
}
