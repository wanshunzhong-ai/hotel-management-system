package com.hotel.hms.repository;

import com.hotel.hms.entity.Bill;
import com.hotel.hms.entity.BillStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    /**
     * 已结账账单的营收合计（<b>应收口径</b> {@code totalAmount}）。
     * <p>
     * 注意不要用 {@code paidAmount} 求和：那是退房时客人补付的金额，
     * 押金抵扣的部分会漏计（押金 200 + 补付 800 的 1000 元订单只算 800）。
     */
    @Query("""
            select coalesce(sum(b.totalAmount), 0) from Bill b
            where b.status = com.hotel.hms.entity.BillStatus.SETTLED
            """)
    BigDecimal sumSettledRevenue();

    /** 指定结账时间区间内的营收合计（应收口径） */
    @Query("""
            select coalesce(sum(b.totalAmount), 0) from Bill b
            where b.status = com.hotel.hms.entity.BillStatus.SETTLED
              and b.settledAt >= :from and b.settledAt < :to
            """)
    BigDecimal sumSettledRevenueBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 按「结账日」分组的营收合计（应收口径），用于近 N 日趋势图。
     * 只返回有数据的天，缺失日期由服务层补 0。
     *
     * @return 每行为 {@code [年, 月, 日, 金额]}
     */
    @Query("""
            select year(b.settledAt), month(b.settledAt), day(b.settledAt), coalesce(sum(b.totalAmount), 0)
            from Bill b
            where b.status = com.hotel.hms.entity.BillStatus.SETTLED
              and b.settledAt >= :from and b.settledAt < :to
            group by year(b.settledAt), month(b.settledAt), day(b.settledAt)
            """)
    List<Object[]> sumSettledRevenueGroupByDay(@Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);

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
