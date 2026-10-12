package com.hotel.hms.repository;

import com.hotel.hms.entity.PayMethod;
import com.hotel.hms.entity.PaymentRecord;
import com.hotel.hms.entity.PaymentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收款流水仓库（第六批）。
 */
@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {

    List<PaymentRecord> findByReservationIdOrderByIdDesc(Long reservationId);

    List<PaymentRecord> findByBillIdOrderByIdAsc(Long billId);

    /** 删除某账单关联的结账类流水（反结账时清理「结账收款 / 找零」） */
    void deleteByBillId(Long billId);

    /**
     * 订单的<b>预收款净额</b> = （预订金 + 押金）− 已退押金。
     *
     * <p>这是账单上「押金」字段的真实口径：不再由前台手填，而是流水的汇总结果。
     * 退房结算时用 {@code 应收 − 预收款净额} 得出还应补付多少。
     */
    @Query("""
            select coalesce(sum(case
                        when p.type = com.hotel.hms.entity.PaymentType.DEPOSIT_REFUND then -p.amount
                        else p.amount end), 0)
            from PaymentRecord p
            where p.reservation.id = :reservationId
              and p.type in (com.hotel.hms.entity.PaymentType.BOOKING_DEPOSIT,
                             com.hotel.hms.entity.PaymentType.DEPOSIT_IN,
                             com.hotel.hms.entity.PaymentType.DEPOSIT_REFUND)
            """)
    BigDecimal sumPrepaymentNet(@Param("reservationId") Long reservationId);

    /** 订单已收押金合计（不含预订金、不含退还） */
    @Query("""
            select coalesce(sum(p.amount), 0) from PaymentRecord p
            where p.reservation.id = :reservationId
              and p.type = com.hotel.hms.entity.PaymentType.DEPOSIT_IN
            """)
    BigDecimal sumDepositIn(@Param("reservationId") Long reservationId);

    /** 订单已退押金合计 */
    @Query("""
            select coalesce(sum(p.amount), 0) from PaymentRecord p
            where p.reservation.id = :reservationId
              and p.type = com.hotel.hms.entity.PaymentType.DEPOSIT_REFUND
            """)
    BigDecimal sumDepositRefund(@Param("reservationId") Long reservationId);

    /**
     * 交接班汇总：按「支付方式 × 流水类型」聚合区间内的收款。
     *
     * @return 每行为 {@code [PayMethod, PaymentType, 金额合计, 笔数]}
     */
    @Query("""
            select p.payMethod, p.type, coalesce(sum(p.amount), 0), count(p)
            from PaymentRecord p
            where p.occurredAt >= :from and p.occurredAt < :to
            group by p.payMethod, p.type
            """)
    List<Object[]> summarizeBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内的流水笔数 */
    long countByOccurredAtGreaterThanEqualAndOccurredAtLessThan(LocalDateTime from, LocalDateTime to);

    /** 区间内的流水明细（交接班打印核对用） */
    @Query("""
            select p from PaymentRecord p
            where p.occurredAt >= :from and p.occurredAt < :to
            order by p.id asc
            """)
    List<PaymentRecord> summarizeRecordsBetween(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /** 区间内有资金往来的订单数（去重，交接核对工作量用） */
    @Query("""
            select count(distinct p.reservation.id) from PaymentRecord p
            where p.occurredAt >= :from and p.occurredAt < :to
            """)
    long countDistinctOrdersBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内某类流水的合计（如结账收款总额） */
    @Query("""
            select coalesce(sum(p.amount), 0) from PaymentRecord p
            where p.occurredAt >= :from and p.occurredAt < :to
              and p.type = :type
            """)
    BigDecimal sumByTypeBetween(@Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                @Param("type") PaymentType type);

    /** 收款流水账查询（分页 + 多条件筛选） */
    @Query("""
            select p from PaymentRecord p
            where (:keyword is null
                   or p.reservation.orderNo like concat('%', :keyword, '%')
                   or p.reservation.customer.name like concat('%', :keyword, '%')
                   or p.reservation.room.roomNo like concat('%', :keyword, '%'))
              and (:type is null or p.type = :type)
              and (:method is null or p.payMethod = :method)
              and (:from is null or p.occurredAt >= :from)
              and (:to is null or p.occurredAt < :to)
            """)
    Page<PaymentRecord> search(@Param("keyword") String keyword,
                               @Param("type") PaymentType type,
                               @Param("method") PayMethod method,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               Pageable pageable);
}
